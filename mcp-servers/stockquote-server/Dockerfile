FROM python:3.11-slim

WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install -r requirements.txt

# Copy application code
COPY server.py .

# Expose port
EXPOSE 8000

# Run the server
CMD ["python", "server.py"]