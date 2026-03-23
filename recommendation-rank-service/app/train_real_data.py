"""
DeepFM Model Training Script
Uses real data from MySQL database to train the model
"""

import sys
import os

# Add parent directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# Change working directory to app folder
os.chdir(os.path.dirname(os.path.abspath(__file__)))

from app.model import DeepFMRanker
from app.features import RealDataGenerator
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Database config (for host machine connection)
DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "user": "root",
    "password": "root123",
    "database": "ecommerce"
}

# Model save path
MODEL_PATH = "models/deepfm_real.pt"

def main():
    logger.info("=" * 60)
    logger.info("DeepFM Model Training with Real Data")
    logger.info("=" * 60)

    # Step 1: Load data from database
    logger.info("\n[Step 1] Loading data from MySQL database...")
    data_generator = RealDataGenerator(DB_CONFIG)

    try:
        user_features, item_features, labels = data_generator.load_interactions_from_db(
            min_interactions=5
        )

        if not labels:
            logger.error("No training data available!")
            return

        logger.info(f"Loaded {len(labels)} samples")
        logger.info(f"Positive samples: {sum(labels)}")
        logger.info(f"Negative samples: {len(labels) - sum(labels)}")

        # Step 2: Split data
        logger.info("\n[Step 2] Splitting data into train/validation sets...")
        import random
        random.seed(42)

        indices = list(range(len(labels)))
        random.shuffle(indices)

        split_idx = int(len(labels) * 0.8)
        train_indices = indices[:split_idx]
        val_indices = indices[split_idx:]

        train_data = {
            "user_features": [user_features[i] for i in train_indices],
            "item_features": [item_features[i] for i in train_indices],
            "labels": [labels[i] for i in train_indices]
        }

        val_data = {
            "user_features": [user_features[i] for i in val_indices],
            "item_features": [item_features[i] for i in val_indices],
            "labels": [labels[i] for i in val_indices]
        }

        logger.info(f"Training samples: {len(train_data['labels'])}")
        logger.info(f"Validation samples: {len(val_data['labels'])}")

        # Step 3: Train model
        logger.info("\n[Step 3] Training DeepFM model...")
        ranker = DeepFMRanker()

        # Create models directory
        os.makedirs("models", exist_ok=True)

        history = ranker.train(
            train_data=train_data,
            val_data=val_data,
            epochs=10,
            batch_size=256,
            save_path=MODEL_PATH
        )

        # Step 4: Print final metrics
        logger.info("\n" + "=" * 60)
        logger.info("Training Complete!")
        logger.info("=" * 60)

        if train_data and history.get("train_metrics"):
            final_train = history["train_metrics"][-1]
            logger.info(f"Final Training AUC: {final_train.get('auc', 0):.4f}")
            logger.info(f"Final Training LogLoss: {final_train.get('logloss', 0):.4f}")

        if val_data and history.get("val_metrics"):
            final_val = history["val_metrics"][-1]
            logger.info(f"Final Validation AUC: {final_val.get('auc', 0):.4f}")
            logger.info(f"Final Validation LogLoss: {final_val.get('logloss', 0):.4f}")

        logger.info(f"\nModel saved to: {MODEL_PATH}")

    finally:
        data_generator.close()


if __name__ == "__main__":
    main()
