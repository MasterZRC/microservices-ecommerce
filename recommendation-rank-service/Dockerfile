FROM python:3.10-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --trusted-host files.pythonhosted.org --trusted-host pypi.org \
    fastapi==0.109.0 uvicorn==0.27.0 numpy==1.26.3 pydantic==2.5.3 \
    pymysql==1.1.0 scikit-learn==1.4.0 pyyaml==6.0.1 && \
    pip install --no-cache-dir torch==2.2.0 --index-url https://download.pytorch.org/whl/cpu

COPY app ./app

EXPOSE 8010

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8010"]
