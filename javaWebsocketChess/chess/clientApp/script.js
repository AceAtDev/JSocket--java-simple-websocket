const chessboardDiv = document.getElementById('chessboard');
const statusDiv = document.getElementById('status');
const turnIndicatorDiv = document.getElementById('turn-indicator');
const playerRoleDiv = document.getElementById('player-role');
const messagesDiv = document.getElementById('messages');
const lastMoveInfoDiv = document.getElementById('last-move-info');

const wsUrl = `ws://${window.location.hostname}:8080`;
let socket;

let currentBoardState = null; // Will hold the 8x8 array from the server
let selectedSquare = null;
let myPlayerRole = null;
let isMyTurn = false;
let lastMoveSquares = { from: null, to: null }; // Track last move squares for highlighting

// Draw an empty board initially to ensure the grid is visible
drawBoard(Array(8).fill().map(() => Array(8).fill(null)));
logMessage("Client: Initial empty board drawn.");

function connect() {
    socket = new WebSocket(wsUrl);

    socket.onopen = function(event) {
        logMessage("Client: Connected to WebSocket server.", "success");
        statusDiv.textContent = "Connected. Waiting for game...";
    };

    socket.onmessage = function(event) {
        logMessage(`Server: ${event.data}`, "server-msg");
        try {
            const data = JSON.parse(event.data);
            handleServerMessage(data);
        } catch (e) {
            logMessage(`Client: Error parsing JSON: ${e.message}`, "error");
            statusDiv.textContent = "Error processing server message.";
        }
    };

    socket.onclose = function(event) {
        logMessage(`Client: Disconnected: ${event.reason || 'Unknown'} (Code: ${event.code})`, "error");
        statusDiv.textContent = "Disconnected. Attempting to reconnect...";
        resetGameState();
        setTimeout(connect, 3000);
    };

    socket.onerror = function(error) {
        logMessage(`Client: WebSocket Error occurred. Check browser console for details.`, "error");
        statusDiv.textContent = "WebSocket connection error.";
    };
}

function resetGameState() {
    currentBoardState = null;
    selectedSquare = null;
    myPlayerRole = null;
    isMyTurn = false;
    lastMoveSquares = { from: null, to: null };
    playerRoleDiv.textContent = "Your Role: Not assigned";
    turnIndicatorDiv.textContent = "Turn: Disconnected";
    turnIndicatorDiv.classList.remove('my-turn');
    lastMoveInfoDiv.textContent = "Last move: N/A";
    drawBoard(Array(8).fill().map(() => Array(8).fill(null)));
}

function handleServerMessage(data) {
    if (data.message) { 
        statusDiv.textContent = data.message;
        if (data.type === "error") { 
            statusDiv.style.backgroundColor = "#f8d7da";
            statusDiv.style.color = "#721c24";
        } else {
            statusDiv.style.backgroundColor = ""; // Reset to default
            statusDiv.style.color = "";
        }
    }

    if (data.role) {
        myPlayerRole = data.role;
        playerRoleDiv.textContent = `Your Role: ${myPlayerRole}`;
        
        // If board data is already available, redraw it with proper orientation
        if (currentBoardState) {
            drawBoard(currentBoardState);
        }
    }

    if (data.board) {
        logMessage("Client: Received board data from server.");
        currentBoardState = data.board;
        
        // Extract move coordinates for highlighting if available
        if (data.move && data.move.length === 4) {
            lastMoveSquares = {
                from: data.move.substring(0, 2),
                to: data.move.substring(2, 4)
            };
        }
        
        drawBoard(currentBoardState);
    }
    
    if (data.type === "game_start") {
        turnIndicatorDiv.textContent = "Turn: Player 1 (White)";
        isMyTurn = (myPlayerRole === "Player 1 (White)");
        updateTurnIndicator();
        lastMoveInfoDiv.textContent = "Last move: N/A";
        if (!data.board) logMessage("Client: game_start message missing board data!", "error");
    } else if (data.type === "opponent_move") {
        turnIndicatorDiv.textContent = `Turn: Your turn`;
        isMyTurn = true;
        updateTurnIndicator();
        
        // Build a more detailed move info display
        let moveInfo = "";
        if (data.move) {
            moveInfo = `${data.move.toUpperCase()}`;
            
            // Special move indicators
            if (data.specialMove) {
                switch(data.specialMove) {
                    case "castling_kingside":
                        moveInfo += " (O-O)";
                        break;
                    case "castling_queenside":
                        moveInfo += " (O-O-O)";
                        break;
                    case "en_passant":
                        moveInfo += " (e.p.)";
                        break;
                }
            }
            
            // Capture info
            if (data.captured) {
                moveInfo += ` (Captured: ${getPieceSymbol(data.captured)})`;
            }
            
            // Promotion info
            if (data.promoted) {
                moveInfo += ` → ${getPieceSymbol(data.promoted)}`;
            }
            
            // Check indicator
            if (data.isCheck) {
                moveInfo += " +";
                showCheckIndicator();
            } else {
                hideCheckIndicator();
            }
            
            // Checkmate/Stalemate
            if (data.isCheckmate) {
                moveInfo += "#";
            }
            
            lastMoveInfoDiv.textContent = `Last move: ${moveInfo}`;
        }
    } else if (data.type === "move_ack") {
        const opponentRole = myPlayerRole === "Player 1 (White)" ? "Player 2 (Black)" : "Player 1 (White)";
        turnIndicatorDiv.textContent = `Turn: ${opponentRole}'s turn`;
        isMyTurn = false;
        updateTurnIndicator();
        
        // Build a more detailed move info display (same as above)
        let moveInfo = "";
        if (data.move) {
            moveInfo = `${data.move.toUpperCase()}`;
            
            // Special move indicators
            if (data.specialMove) {
                switch(data.specialMove) {
                    case "castling_kingside":
                        moveInfo += " (O-O)";
                        break;
                    case "castling_queenside":
                        moveInfo += " (O-O-O)";
                        break;
                    case "en_passant":
                        moveInfo += " (e.p.)";
                        break;
                }
            }
            
            // Capture info
            if (data.captured) {
                moveInfo += ` (Captured: ${getPieceSymbol(data.captured)})`;
            }
            
            // Promotion info
            if (data.promoted) {
                moveInfo += ` → ${getPieceSymbol(data.promoted)}`;
            }
            
            // Check indicator
            if (data.isCheck) {
                moveInfo += " +";
                // We don't show check indicator for our own king
            }
            
            // Checkmate
            if (data.isCheckmate) {
                moveInfo += "#";
            }
            
            lastMoveInfoDiv.textContent = `Last move: ${moveInfo}`;
        }
    } else if (data.type === "opponent_disconnected") {
        turnIndicatorDiv.textContent = "Game Over: Opponent Disconnected";
        isMyTurn = false;
        updateTurnIndicator();
        statusDiv.textContent = "Opponent disconnected. Game over.";
        showGameEndMessage("Opponent disconnected. Game over.");
    } else if (data.type === "game_over") {
        turnIndicatorDiv.textContent = "Game Over";
        isMyTurn = false;
        updateTurnIndicator();
        
        let resultMessage = "Game over: ";
        if (data.result === "checkmate") {
            resultMessage += `Checkmate! ${data.winner} wins!`;
        } else if (data.result === "stalemate") {
            resultMessage += "Stalemate! The game is a draw.";
        } else {
            resultMessage += data.message || "The game has ended.";
        }
        
        showGameEndMessage(resultMessage);
    } else if (data.type === "error") { 
        logMessage(`Client: Server error: ${data.message}`, "error");
    }
}

function showCheckIndicator() {
    // Highlight the king that's in check
    const kingInCheck = findKingSquare(myPlayerRole === "Player 1 (White)");
    if (kingInCheck) {
        kingInCheck.classList.add('in-check');
    }
}
function hideCheckIndicator() {
    const checkElements = document.querySelectorAll('.in-check');
    checkElements.forEach(el => el.classList.remove('in-check'));
}
function showGameEndMessage(message) {
    // Create or update game end overlay
    let gameEndOverlay = document.getElementById('game-end-overlay');
    
    if (!gameEndOverlay) {
        gameEndOverlay = document.createElement('div');
        gameEndOverlay.id = 'game-end-overlay';
        document.querySelector('.board-container').appendChild(gameEndOverlay);
    }
    
    gameEndOverlay.innerHTML = `
        <div class="game-end-message">
            <h2>Game Over</h2>
            <p>${message}</p>
            <button id="new-game-btn">Play Again</button>
        </div>
    `;
    
    gameEndOverlay.style.display = 'flex';
    
    // Add event listener to play again button
    document.getElementById('new-game-btn').addEventListener('click', () => {
        // This could reconnect or request a new game from the server
        // For now, just refresh the page
        window.location.reload();
    });
}


function findKingSquare(findWhiteKing) {
    if (!currentBoardState) return null;
    
    const kingToFind = findWhiteKing ? "wK" : "bK";
    
    // Iterate through the board squares
    const squares = document.querySelectorAll('.square');
    for (const square of squares) {
        const row = parseInt(square.dataset.row);
        const col = parseInt(square.dataset.col);
        
        if (currentBoardState[row] && currentBoardState[row][col] === kingToFind) {
            return square;
        }
    }
    return null;
}




function updateTurnIndicator() {
    if (isMyTurn) {
        turnIndicatorDiv.classList.add('my-turn');
    } else {
        turnIndicatorDiv.classList.remove('my-turn');
    }
}

function drawBoard(boardData) {
    chessboardDiv.innerHTML = ''; 
    if (!boardData || !Array.isArray(boardData) || boardData.length !== 8) {
        logMessage("Client: Invalid or empty boardData for drawing. Displaying empty grid.", "error");
        boardData = Array(8).fill().map(() => Array(8).fill(null));
    }
    
    // Determine if we should flip the board (for Player 2/Black)
    const shouldFlipBoard = myPlayerRole === "Player 2 (Black)";
    
    // Update any file/rank labels if they exist
    updateBoardLabels(shouldFlipBoard);
    
    // Loop through ranks (rows)
    for (let displayRow = 0; displayRow < 8; displayRow++) {
        // If board is flipped, we invert the display order for Player 2
        // For Player 1 (normal): displayRow 0 shows boardRowIndex 7 (rank 8)
        // For Player 2 (flipped): displayRow 0 shows boardRowIndex 0 (rank 1)
        const boardRowIndex = shouldFlipBoard ? displayRow : 7 - displayRow;
        
        if (!boardData[boardRowIndex] || !Array.isArray(boardData[boardRowIndex]) || boardData[boardRowIndex].length !== 8) {
            logMessage(`Client: Invalid row data at boardRowIndex ${boardRowIndex}. Drawing empty row.`, "error");
            for (let col = 0; col < 8; col++) {
                const emptySquare = document.createElement('div');
                emptySquare.classList.add('square');
                emptySquare.classList.add(((shouldFlipBoard ? 7 - displayRow : displayRow) + col) % 2 === 0 ? 'light' : 'dark');
                chessboardDiv.appendChild(emptySquare);
            }
            continue; 
        }

        // Loop through files (columns)
        for (let displayCol = 0; displayCol < 8; displayCol++) {
            // Similar flipping logic for columns
            // For Player 1: displayCol 0 shows col 0 (file 'a')
            // For Player 2: displayCol 0 shows col 7 (file 'h')
            const col = shouldFlipBoard ? 7 - displayCol : displayCol;
            
            const square = document.createElement('div');
            square.classList.add('square');
            
            // The color pattern needs to be maintained regardless of orientation
            // We use the virtual "board coordinates" to determine color
            const isLightSquare = (boardRowIndex + col) % 2 !== 0;
            square.classList.add(isLightSquare ? 'light' : 'dark');
            
            const piece = boardData[boardRowIndex][col]; 
            const algebraic = indicesToAlgebraic(boardRowIndex, col);
            
            // Add last move highlighting
            if (algebraic === lastMoveSquares.from) {
                square.classList.add('highlight-last-move-from');
            }
            if (algebraic === lastMoveSquares.to) {
                square.classList.add('highlight-last-move-to');
            }
            
            if (piece) {
                square.textContent = getPieceSymbol(piece);
                square.classList.add(piece.startsWith('w') ? 'piece-white' : 'piece-black');
            }
            
            // Store the "logical" coordinates, not display coordinates
            square.dataset.row = boardRowIndex;
            square.dataset.col = col;
            square.dataset.algebraic = algebraic;

            square.addEventListener('click', onSquareClick);
            chessboardDiv.appendChild(square);
        }
    }
    logMessage("Client: Board redrawn. Orientation: " + (shouldFlipBoard ? "Flipped (Black's view)" : "Normal (White's view)"));
}

function updateBoardLabels(flipped) {
    // Get the file and rank labels containers if they exist
    const fileLabelsDiv = document.querySelector('.file-labels');
    const rankLabelsDiv = document.querySelector('.rank-labels');
    
    if (fileLabelsDiv) {
        fileLabelsDiv.innerHTML = ''; // Clear existing labels
        
        // Create file labels (a-h)
        const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
        if (flipped) files.reverse();
        
        files.forEach(file => {
            const span = document.createElement('span');
            span.textContent = file;
            fileLabelsDiv.appendChild(span);
        });
    }
    
    if (rankLabelsDiv) {
        rankLabelsDiv.innerHTML = ''; // Clear existing labels
        
        // Create rank labels (1-8)
        const ranks = ['1', '2', '3', '4', '5', '6', '7', '8'];
        if (!flipped) ranks.reverse(); // Normal view has 8 at top
        
        ranks.forEach(rank => {
            const span = document.createElement('span');
            span.textContent = rank;
            rankLabelsDiv.appendChild(span);
        });
    }
}

function getPieceSymbol(pieceCode) {
    if (!pieceCode || typeof pieceCode !== 'string') return '';
    const type = pieceCode.substring(1); 
    const isWhite = pieceCode.startsWith('w');
    switch(type) {
        case 'P': return isWhite ? '♙' : '♟';
        case 'R': return isWhite ? '♖' : '♜';
        case 'N': return isWhite ? '♘' : '♞';
        case 'B': return isWhite ? '♗' : '♝';
        case 'Q': return isWhite ? '♕' : '♛';
        case 'K': return isWhite ? '♔' : '♚';
        default: 
            return pieceCode; 
    }
}

function onSquareClick(event) {
    if (!isMyTurn) {
        logMessage("Client: Not your turn.", "info");
        return;
    }
    if (!currentBoardState) { 
        logMessage("Client: Board state not available.", "error");
        return;
    }

    const clickedSquareDiv = event.currentTarget;
    const row = parseInt(clickedSquareDiv.dataset.row);
    const col = parseInt(clickedSquareDiv.dataset.col);
    const algebraic = clickedSquareDiv.dataset.algebraic;
    const piece = (currentBoardState && currentBoardState[row]) ? currentBoardState[row][col] : null;

    if (selectedSquare) { 
        if (selectedSquare.row === row && selectedSquare.col === col) {
            selectedSquare.div.classList.remove('selected');
            selectedSquare = null;
            return;
        }
        const fromAlg = selectedSquare.algebraic;
        const toAlg = algebraic;
        
        // Check for pawn promotion
        const isPawnPromotion = selectedSquare.piece.endsWith("P") && 
                              ((myPlayerRole === "Player 1 (White)" && toAlg[1] === "8") || 
                               (myPlayerRole === "Player 2 (Black)" && toAlg[1] === "1"));
        
        if (isPawnPromotion) {
            showPromotionSelector(fromAlg, toAlg, myPlayerRole === "Player 1 (White)");
            selectedSquare.div.classList.remove('selected');
            selectedSquare = null;
            return;
        }
        
        const moveString = fromAlg + toAlg;
        sendMove(moveString);
        
        selectedSquare.div.classList.remove('selected');
        selectedSquare = null;
    } else { 
        if (!piece) {
            return; 
        }
        const isWhitePiece = piece.startsWith('w');
        if ((myPlayerRole === "Player 1 (White)" && !isWhitePiece) ||
            (myPlayerRole === "Player 2 (Black)" && isWhitePiece)) {
            logMessage("Client: Cannot select opponent's piece.", "error");
            return;
        }
        selectedSquare = { row, col, piece, algebraic, div: clickedSquareDiv };
        clickedSquareDiv.classList.add('selected');
        logMessage(`Client: Selected ${getPieceSymbol(piece)} at ${algebraic}`);
    }
}

// Helper function for sending moves
function sendMove(moveString) {
    logMessage(`Client: Attempting move: ${moveString}`);
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(moveString);
    } else {
        logMessage("Client: WebSocket not connected. Cannot send move.", "error");
    }
}

// Add function to show promotion selection UI
function showPromotionSelector(fromAlg, toAlg, isWhite) {
    // Create promotion selection overlay
    let promotionOverlay = document.getElementById('promotion-overlay');
    
    if (!promotionOverlay) {
        promotionOverlay = document.createElement('div');
        promotionOverlay.id = 'promotion-overlay';
        document.querySelector('.board-container').appendChild(promotionOverlay);
    }
    
    const pieces = [
        { type: 'q', symbol: isWhite ? '♕' : '♛', name: 'Queen' },
        { type: 'r', symbol: isWhite ? '♖' : '♜', name: 'Rook' },
        { type: 'b', symbol: isWhite ? '♗' : '♝', name: 'Bishop' },
        { type: 'n', symbol: isWhite ? '♘' : '♞', name: 'Knight' }
    ];
    
    let buttonsHTML = '';
    pieces.forEach(piece => {
        buttonsHTML += `
            <button class="promotion-piece ${isWhite ? 'piece-white' : 'piece-black'}" 
                    data-piece-type="${piece.type}" 
                    title="${piece.name}">
                ${piece.symbol}
            </button>
        `;
    });
    
    promotionOverlay.innerHTML = `
        <div class="promotion-selection">
            <h3>Promote Your Pawn</h3>
            <div class="promotion-pieces">
                ${buttonsHTML}
            </div>
        </div>
    `;
    
    promotionOverlay.style.display = 'flex';
    
    // Add event listeners to promotion buttons
    document.querySelectorAll('.promotion-piece').forEach(button => {
        button.addEventListener('click', () => {
            const pieceType = button.getAttribute('data-piece-type');
            const moveString = fromAlg + toAlg + pieceType;
            sendMove(moveString);
            promotionOverlay.style.display = 'none';
        });
    });
}

function indicesToAlgebraic(row, col) {
    const file = String.fromCharCode('a'.charCodeAt(0) + col);
    const rank = (row + 1).toString();
    return file + rank;
}

function logMessage(message, type = "info") { 
    const p = document.createElement('p');
    p.textContent = message;
    p.className = type;
    messagesDiv.appendChild(p);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

// Start the connection
connect();