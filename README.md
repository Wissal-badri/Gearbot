# Gearbot
Running the Chatbot

This project requires an API key to function. The key is not included in the repository to keep it private.

How to run it:

Set your API key

On Windows, set it in PowerShell using:
$env:GEMINI_API_KEY="YOUR_OWN_API_KEY"

On Mac/Linux, set it in Terminal using:
export GEMINI_API_KEY="YOUR_OWN_API_KEY"

Build the project using Maven (./mvnw clean package).

Run the application: the chatbot will start locally and be accessible at http://localhost:8080.

Important: Each user must provide their own API key for the chatbot to work.
