#!/usr/bin/env python3
"""Record the README demo: two browsers playing chess through the JSocket server.

What it does:
  1. Compiles the Java sources with javac and starts ChessServerMain.
  2. Opens the chess client in two separate Playwright browser contexts
     (Player 1 White, Player 2 Black) and records each one with record_video.
  3. Plays a short opening by clicking squares, alternating players. Every move
     goes through the real WebSocket server.
  4. Uses ffmpeg to put the two recordings side by side with a label over each,
     and writes docs/media/chess-demo.mp4 and docs/media/chess-demo.gif.

Requirements: JDK (javac/java), ffmpeg, Python Playwright with Chromium
(`pip install playwright && playwright install chromium`).

Usage (from the repository root):
  python docs/demo/record_demo.py              # server on port 8080
  python docs/demo/record_demo.py --port 8765  # if 8080 is busy
"""

import argparse
import asyncio
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from playwright.async_api import async_playwright

REPO = Path(__file__).resolve().parents[2]
CLIENT_HTML = REPO / "javaWebsocketChess" / "chess" / "clientApp" / "index.html"
MEDIA = REPO / "docs" / "media"

WIDTH, HEIGHT = 640, 720          # size of each browser window
# Region of each window kept in the output (w, h, x, y): the board and the
# status panels, without the empty page margins.
CROP_W, CROP_H, CROP_X, CROP_Y = 440, 690, 100, 10
LABEL_BAR = 40                     # height of the label bar above each window
DIVIDER = 4                        # gap between the two windows

# (player index, from, to). 0 = White, 1 = Black. Italian Game opening.
MOVES = [
    (0, "e2", "e4"),
    (1, "e7", "e5"),
    (0, "g1", "f3"),
    (1, "b8", "c6"),
    (0, "f1", "c4"),
    (1, "g8", "f6"),
]


def label_font():
    """ffmpeg drawtext font option: a bold sans font file if fontconfig finds one."""
    try:
        path = subprocess.run(["fc-match", "-f", "%{file}", "DejaVu Sans:bold"],
                              capture_output=True, text=True, check=True).stdout.strip()
        if path:
            return f"fontfile='{path}'"
    except (OSError, subprocess.CalledProcessError):
        pass
    return "font=Sans"


def wait_for_port(port, timeout=15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("localhost", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError(f"server did not open port {port}")


def start_server(build_dir, port):
    sources = [str(p) for p in (REPO / "javaWebsocketChess").rglob("*.java")]
    subprocess.run(["javac", "-d", str(build_dir), *sources], check=True)
    log = open(build_dir / "server.log", "w")
    proc = subprocess.Popen(
        ["java", "-cp", str(build_dir), "javaWebsocketChess.chess.ChessServerMain", str(port)],
        stdout=log, stderr=subprocess.STDOUT,
    )
    wait_for_port(port)
    return proc


async def click_square(page, square):
    await page.click(f'#chessboard [data-algebraic="{square}"]')


async def record(port, video_dir):
    url = f"{CLIENT_HTML.as_uri()}?port={port}"
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        contexts, pages, created = [], [], []
        for _ in range(2):
            ctx = await browser.new_context(
                viewport={"width": WIDTH, "height": HEIGHT},
                record_video_dir=str(video_dir),
                record_video_size={"width": WIDTH, "height": HEIGHT},
            )
            page = await ctx.new_page()
            created.append(time.monotonic())  # video for this page starts about now
            contexts.append(ctx)
            pages.append(page)

        # Player 1 connects first and waits; Player 2 joins and the game starts.
        await pages[0].goto(url)
        await pages[0].wait_for_selector("text=Your Role: Player 1 (White)")
        await asyncio.sleep(0.5)
        await pages[1].goto(url)
        start = time.monotonic()  # clip starts once both pages have loaded
        for page in pages:
            await page.wait_for_selector("text=Game started!")
        await asyncio.sleep(1.0)

        for player, src, dst in MOVES:
            mover, other = pages[player], pages[1 - player]
            await click_square(mover, src)
            await asyncio.sleep(0.5)
            await click_square(mover, dst)
            # The opponent's board only changes after the server relays the move.
            await other.wait_for_selector(
                f'#chessboard [data-algebraic="{dst}"].highlight-last-move-to')
            await other.wait_for_selector("#turn-indicator.my-turn")
            await asyncio.sleep(1.0)
        end = time.monotonic() + 0.5
        await asyncio.sleep(0.8)

        paths = []
        for ctx, page in zip(contexts, pages):
            await ctx.close()  # finalizes the video file
            paths.append(Path(await page.video.path()))
        await browser.close()

    offsets = [start - c for c in created]
    return paths, offsets, end - start


def compose(paths, offsets, duration):
    MEDIA.mkdir(parents=True, exist_ok=True)
    mp4 = MEDIA / "chess-demo.mp4"
    gif = MEDIA / "chess-demo.gif"

    font = label_font()

    def label(text):
        return (f"crop={CROP_W}:{CROP_H}:{CROP_X}:{CROP_Y},"
                f"pad={CROP_W}:{CROP_H + LABEL_BAR}:0:{LABEL_BAR}:color=0x1f2933,"
                f"drawtext={font}:text='{text}':fontsize=22:fontcolor=white:"
                f"x=(w-text_w)/2:y=({LABEL_BAR}-text_h)/2")

    filt = (
        f"[0:v]setpts=PTS-STARTPTS,{label('Player 1 (White)')},"
        f"pad=iw+{DIVIDER}:ih:0:0:color=0x1f2933[l];"
        f"[1:v]setpts=PTS-STARTPTS,{label('Player 2 (Black)')}[r];"
        f"[l][r]hstack=inputs=2,fps=25,format=yuv420p[v]"
    )
    inputs = []
    for path, off in zip(paths, offsets):
        inputs += ["-ss", f"{off:.3f}", "-t", f"{duration:.3f}", "-i", str(path)]
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", *inputs, "-filter_complex", filt,
         "-map", "[v]", "-c:v", "libx264", "-preset", "slow", "-crf", "28",
         "-movflags", "+faststart", "-an", str(mp4)],
        check=True,
    )
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp4), "-filter_complex",
         "fps=10,split[a][b];"
         "[a]palettegen=max_colors=128:stats_mode=diff[p];"
         "[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
         "-loop", "0", str(gif)],
        check=True,
    )
    return mp4, gif


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    for tool in ("javac", "java", "ffmpeg"):
        if not shutil.which(tool):
            sys.exit(f"{tool} not found on PATH")

    with tempfile.TemporaryDirectory(prefix="jsocket-demo-") as tmp:
        tmp = Path(tmp)
        (tmp / "build").mkdir()
        server = start_server(tmp / "build", args.port)
        try:
            paths, offsets, duration = asyncio.run(record(args.port, tmp / "video"))
        finally:
            server.terminate()
            server.wait(timeout=10)
        mp4, gif = compose(paths, offsets, duration)

    for f in (mp4, gif):
        print(f"{f.relative_to(REPO)}  {f.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
