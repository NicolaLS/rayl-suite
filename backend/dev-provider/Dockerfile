FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt provider.py ./
RUN pip install --no-cache-dir -r requirements.txt
ENV HOST=0.0.0.0
CMD ["python", "provider.py"]
