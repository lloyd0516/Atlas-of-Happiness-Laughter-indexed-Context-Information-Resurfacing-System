import os
import asyncio
import logging

# Set up logging
logging.basicConfig(level=logging.DEBUG, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Set environment variables
os.environ["SPEECHMATICS_API_KEY"] = "sBccf1uWZEqz8Yn6VZJMsAeyB6u0YSH4"
os.environ["SPEECHMATICS_RT_URL"] = "wss://eu2.rt.speechmatics.com/v2"
os.environ["SPEECHMATICS_LANGUAGE"] = "en"
os.environ["SPEECHMATICS_EVENT_TYPES"] = "laughter"

async def test_speechmatics_connection():
    """Test basic Speechmatics API connection"""
    try:
        logger.info("Importing Speechmatics modules...")
        from speechmatics_demo.app.config import Settings
        from speechmatics_demo.app.service import RealtimeLaughterService
        from pathlib import Path
        
        logger.info("Creating settings...")
        settings = Settings.from_env()
        
        logger.info("Creating service...")
        service = RealtimeLaughterService(settings)
        
        # Use a test audio file
        audio_path = Path(r"d:\Projects\laughter-detection\datasets_unified\switchboard\clips\test\test_speech_000000.wav")
        if not audio_path.exists():
            logger.error(f"Audio file not found: {audio_path}")
            return
        
        logger.info(f"Reading audio file: {audio_path}")
        wav_bytes = audio_path.read_bytes()
        logger.info(f"Audio file size: {len(wav_bytes)} bytes")
        
        logger.info("Processing audio...")
        result = await service.process_wav_bytes(
            wav_bytes=wav_bytes,
            source_name="test",
            chunk_ms=200,
            pace_realtime=False,
        )
        
        logger.info(f"Processing complete! Result: {result}")
        
    except Exception as e:
        logger.error(f"Error: {e}", exc_info=True)

if __name__ == "__main__":
    logger.info("Starting Speechmatics test...")
    asyncio.run(test_speechmatics_connection())
    logger.info("Test completed!")
