# Gemma 4 Fine-Tuning & Web App Integration Guide
## Final Project Demo - [click here](https://www.kaggle.com/competitions/gemma-4-good-hackathon/writeups/dr-ai-medical-assistant)
## Table of Contents
1. [Fine-Tuning Gemma 4](#fine-tuning-gemma-4)
2. [Integration with Web App](#integration-with-web-app)
3. [Project Structure](#project-structure)
4. [Quick Start](#quick-start)

---

## Fine-Tuning Gemma 4

### Prerequisites

Install the required packages in your project `.venv`:

```bash
pip install transformers torch peft datasets accelerate bitsandbytes
```

Add to your `requirements.txt`:
```
transformers>=4.40.0
torch>=2.1.0
peft>=0.10.0
datasets>=2.14.0
accelerate>=0.26.0
bitsandbytes>=0.41.0
```

### Step 1: Prepare Your Training Data

**Location**: `train/data/` in each project

Create a JSON file with your training examples:

```json
[
  {
    "instruction": "Classify the following medical symptom",
    "input": "Patient reports fever and cough for 3 days",
    "output": "Suspected respiratory infection. Recommend immediate evaluation."
  },
  {
    "instruction": "Provide climate resilience advice",
    "input": "Community in flood-prone area",
    "output": "Implement early warning systems and elevated building codes."
  }
]
```

**Key Tips**:
- Use 100-500 examples for meaningful improvement
- Ensure outputs are domain-specific for your hackathon topic
- Balance variety and consistency
- Use clear, concise instructions

### Step 2: Create Fine-Tuning Script

**Location**: `train/fine_tune/train_gemma4.py`

```python
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
from peft import get_peft_model, LoraConfig, TaskType
from datasets import Dataset
import json
import os

def load_training_data(data_path):
    with open(data_path, 'r') as f:
        data = json.load(f)
    
    # Format as prompts
    texts = []
    for item in data:
        prompt = f"Instruction: {item['instruction']}\nInput: {item['input']}\nOutput: {item['output']}"
        texts.append({"text": prompt})
    
    return Dataset.from_list(texts)

def setup_quantization():
    """Setup 4-bit quantization for memory efficiency"""
    return BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
    )

def setup_peft_config():
    """Setup LoRA for efficient fine-tuning"""
    return LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
        target_modules=["q_proj", "v_proj"]
    )

def fine_tune_gemma4(model_name, data_path, output_dir, num_epochs=3):
    """Main fine-tuning function"""
    
    # Load model with quantization
    quantization_config = setup_quantization()
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        quantization_config=quantization_config,
        device_map="auto"
    )
    
    # Setup LoRA
    peft_config = setup_peft_config()
    model = get_peft_model(model, peft_config)
    
    # Load tokenizer
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    tokenizer.pad_token = tokenizer.eos_token
    
    # Load data
    dataset = load_training_data(data_path)
    
    # Tokenize
    def tokenize_function(examples):
        return tokenizer(
            examples["text"],
            padding="max_length",
            truncation=True,
            max_length=512
        )
    
    tokenized_dataset = dataset.map(
        tokenize_function,
        batched=True,
        remove_columns=["text"]
    )
    
    # Training configuration
    from transformers import TrainingArguments, Trainer
    
    training_args = TrainingArguments(
        output_dir=output_dir,
        num_train_epochs=num_epochs,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,
        save_steps=50,
        save_total_limit=2,
        learning_rate=2e-4,
        fp16=True,
        optim="paged_adamw_8bit",
        warmup_steps=100,
        logging_steps=10,
    )
    
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_dataset,
        tokenizer=tokenizer,
    )
    
    # Train
    trainer.train()
    
    # Save adapter weights
    model.save_pretrained(os.path.join(output_dir, "adapter_weights"))
    print(f"✓ Fine-tuning complete. Adapter saved to {output_dir}")

if __name__ == "__main__":
    # Example usage
    fine_tune_gemma4(
        model_name="google/gemma-7b",
        data_path="data/training_data.json",
        output_dir="./checkpoints",
        num_epochs=3
    )
```

**Run Fine-Tuning**:
```bash
cd health-sciences/train
python fine_tune/train_gemma4.py
```

### Step 3: Evaluate Your Model

**Location**: `train/fine_tune/evaluate.py`

```python
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM
from peft import PeftModel

def evaluate_finetuned_model(base_model, adapter_weights_path, test_prompts):
    """Load and evaluate fine-tuned model"""
    
    # Load base model
    model = AutoModelForCausalLM.from_pretrained(
        base_model,
        torch_dtype=torch.float16,
        device_map="auto"
    )
    
    # Load adapter weights
    model = PeftModel.from_pretrained(model, adapter_weights_path)
    model = model.merge_and_unload()
    
    tokenizer = AutoTokenizer.from_pretrained(base_model)
    
    print("=== Model Evaluation ===\n")
    
    for prompt in test_prompts:
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        outputs = model.generate(**inputs, max_new_tokens=100)
        response = tokenizer.decode(outputs[0], skip_special_tokens=True)
        print(f"Prompt: {prompt}")
        print(f"Response: {response}\n")

if __name__ == "__main__":
    test_prompts = [
        "Instruction: Classify this disease\nInput: High fever and rash\nOutput:",
        "Instruction: Climate advice\nInput: Drought conditions\nOutput:"
    ]
    
    evaluate_finetuned_model(
        base_model="google/gemma-7b",
        adapter_weights_path="./checkpoints/adapter_weights",
        test_prompts=test_prompts
    )
```

---

## Integration with Web App

### Architecture Overview

```
┌─────────────────┐
│   Web Frontend  │  (React/Vue/HTML)
│   (frontend/)   │
└────────┬────────┘
         │ HTTP REST
         ↓
┌─────────────────┐
│    Backend API  │  (FastAPI/Flask)
│   (backend/)    │
└────────┬────────┘
         │ Local Ollama API
         ↓
┌─────────────────┐
│ Ollama Server   │
│ + Gemma4 Model  │  (Locally hosted)
└─────────────────┘
```

### Step 1: Backend API Setup

**Location**: `backend/app.py`

Using **FastAPI** (recommended for Gemma):

```bash
pip install fastapi uvicorn python-dotenv
```

```python
# backend/app.py
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import os
import sys
import importlib

# Add train folder to path
sys.path.insert(0, '../train')
from gemma4model import chat_with_gemma4

app = FastAPI(title="Gemma 4 API")

# Enable CORS for frontend
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://localhost:8080"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ChatRequest(BaseModel):
    message: str
    system_prompt: str = None

class ChatResponse(BaseModel):
    response: str
    model: str

@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """Send a message to Gemma 4 and get response"""
    try:
        # Use custom system prompt if provided, else use default
        if request.system_prompt:
            # Implement custom system prompt handling here
            response = chat_with_gemma4(request.message)
        else:
            response = chat_with_gemma4(request.message)
        
        return ChatResponse(
            response=response,
            model=os.getenv("OLLAMA_MODEL", "gemma4")
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
async def health_check():
    """Check API and Ollama connectivity"""
    try:
        # Quick test of Ollama connection
        chat_with_gemma4("ping")
        return {"status": "healthy", "ollama": "connected"}
    except Exception as e:
        return {"status": "unhealthy", "error": str(e)}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

**Run Backend**:
```bash
cd health-sciences/backend
python app.py
```

### Step 2: Frontend Integration

**Location**: `frontend/index.html` (Simple HTML + JS example)

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Gemma 4 Chat - Health Sciences</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .container {
            width: 100%;
            max-width: 600px;
            background: white;
            border-radius: 10px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
            overflow: hidden;
        }
        .header {
            background: #667eea;
            color: white;
            padding: 20px;
            text-align: center;
        }
        .chat-box {
            height: 400px;
            overflow-y: auto;
            padding: 20px;
            background: #f9f9f9;
        }
        .message {
            margin: 10px 0;
            padding: 10px 15px;
            border-radius: 8px;
            max-width: 80%;
            word-wrap: break-word;
        }
        .user-message {
            background: #667eea;
            color: white;
            margin-left: auto;
            text-align: right;
        }
        .bot-message {
            background: #e9ecef;
            color: #333;
        }
        .input-area {
            padding: 20px;
            border-top: 1px solid #ddd;
            display: flex;
            gap: 10px;
        }
        input {
            flex: 1;
            padding: 10px 15px;
            border: 1px solid #ddd;
            border-radius: 5px;
            font-size: 14px;
        }
        button {
            padding: 10px 20px;
            background: #667eea;
            color: white;
            border: none;
            border-radius: 5px;
            cursor: pointer;
            font-weight: bold;
        }
        button:hover {
            background: #5568d3;
        }
        .loading {
            text-align: center;
            color: #999;
            font-style: italic;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Gemma 4 Health Sciences Chat</h1>
            <p>Powered by fine-tuned Gemma 4</p>
        </div>
        <div class="chat-box" id="chatBox"></div>
        <div class="input-area">
            <input type="text" id="messageInput" placeholder="Ask a health question..." />
            <button onclick="sendMessage()">Send</button>
        </div>
    </div>

    <script>
        const API_URL = "http://localhost:8000";

        async function sendMessage() {
            const input = document.getElementById("messageInput");
            const message = input.value.trim();
            
            if (!message) return;

            // Display user message
            addMessage(message, "user");
            input.value = "";

            // Show loading indicator
            addMessage("...", "loading");

            try {
                const response = await fetch(`${API_URL}/chat`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ message: message })
                });

                if (!response.ok) throw new Error("API error");
                const data = await response.json();

                // Remove loading indicator
                removeLastMessage();

                // Display bot response
                addMessage(data.response, "bot");
            } catch (error) {
                removeLastMessage();
                addMessage("Error: Could not reach API. Is the backend running?", "bot");
            }
        }

        function addMessage(text, sender) {
            const chatBox = document.getElementById("chatBox");
            const messageDiv = document.createElement("div");
            messageDiv.className = `message ${sender}-message`;
            if (sender === "loading") messageDiv.className = "message loading";
            messageDiv.textContent = text;
            chatBox.appendChild(messageDiv);
            chatBox.scrollTop = chatBox.scrollHeight;
        }

        function removeLastMessage() {
            const chatBox = document.getElementById("chatBox");
            if (chatBox.lastChild) chatBox.removeChild(chatBox.lastChild);
        }

        // Allow Enter key to send
        document.getElementById("messageInput").addEventListener("keypress", (e) => {
            if (e.key === "Enter") sendMessage();
        });

        // Health check on load
        window.addEventListener("load", async () => {
            try {
                const res = await fetch(`${API_URL}/health`);
                const data = await res.json();
                if (data.status !== "healthy") {
                    addMessage("⚠️ Backend is not ready. Make sure Ollama is running and backend is started.", "bot");
                }
            } catch {
                addMessage("⚠️ Cannot reach backend. Start the API with: python backend/app.py", "bot");
            }
        });
    </script>
</body>
</html>
```

**Run Frontend**:
```bash
# Install a simple HTTP server
npm install -g http-server

# Or use Python
cd frontend
python -m http.server 8080
```

Open browser: `http://localhost:8080`

### Step 3: Deployment Checklist

#### Local Development:
```bash
# Terminal 1: Start Ollama
ollama serve

# Terminal 2: Start Backend
cd health-sciences/backend
python app.py

# Terminal 3: Start Frontend
cd health-sciences/frontend
http-server .
```

#### Production Options:

**Option 1: Docker Deployment**
```dockerfile
# Dockerfile for backend
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY backend/ .
COPY train/ ../train

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
```

**Option 2: Cloud Hosting**
- Backend: Deploy to Render, Railway, or AWS Lambda
- Model: Use Ollama API endpoint or inference service
- Frontend: Deploy to Vercel, Netlify

**Option 3: Edge Deployment**
- Use Ollama + lightweight model locally
- Frontend runs in browser
- No internet required for inference

---

## Project Structure

```
health-sciences/
├── train/
│   ├── gemma4model.py              # Chat helper
│   ├── fine_tune/
│   │   ├── train_gemma4.py         # Fine-tuning script
│   │   ├── evaluate.py             # Evaluation script
│   │   └── checkpoints/            # Saved weights
│   ├── rag/
│   │   └── retrieval_data.json     # RAG corpus
│   └── data/
│       └── training_data.json      # Fine-tuning data
├── backend/
│   ├── app.py                      # FastAPI server
│   └── requirements.txt
├── frontend/
│   ├── index.html                  # Chat UI
│   └── package.json                # If using React
├── .env                            # Environment variables
├── .venv/                          # Virtual environment
└── requirements.txt
```

---

## Quick Start

### 1. Fine-Tune Your Model (Optional)
```bash
cd health-sciences/train
python fine_tune/train_gemma4.py
```

### 2. Start Ollama
```bash
ollama serve
```

### 3. Start Backend
```bash
cd health-sciences/backend
source ../.venv/Scripts/activate  # Windows: ..\.venv\Scripts\Activate.ps1
python app.py
```

### 4. Start Frontend
```bash
cd health-sciences/frontend
python -m http.server 8080
```

### 5. Open Browser
Visit `http://localhost:8080`

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Model not found (404) | Run `ollama pull gemma4` or use `ollama list` to see available models |
| CORS error | Check backend CORS config, ensure frontend URL is in allow_origins |
| Backend timeout | Increase `max_tokens` or reduce model size. Check Ollama logs |
| Out of memory | Use quantization (`-q4`) when pulling model |
| Python import errors | Ensure `.venv` is activated and dependencies are installed |

---

## References

- [Ollama Documentation](https://github.com/ollama/ollama)
- [Hugging Face Transformers](https://huggingface.co/docs/transformers)
- [LoRA Fine-tuning](https://huggingface.co/blog/guest-post-scaling-large-language-models-qlora)
- [FastAPI Docs](https://fastapi.tiangolo.com/)
