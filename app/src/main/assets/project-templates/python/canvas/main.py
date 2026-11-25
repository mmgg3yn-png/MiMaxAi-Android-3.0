from js import document

canvas = document.getElementById("python-canvas")
context = canvas.getContext("2d")
context.fillStyle = "#3977e8"
context.fillRect(24, 24, 150, 90)
context.fillStyle = "#ffffff"
context.font = "16px sans-serif"
context.fillText("Python Canvas", 38, 74)
print("Canvas rendered from Python using the browser canvas API.")
