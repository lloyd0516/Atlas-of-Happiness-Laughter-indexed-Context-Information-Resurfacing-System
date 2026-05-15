import os
import asyncio
from speechmatics_demo.app.config import Settings
from speechmatics_demo.app.service import RealtimeLaughterService
from pathlib import Path

# Set environment variables
os.environ["SPEECHMATICS_API_KEY"] = "sBccf1uWZEqz8Yn6VZJMsAeyB6u0YSH4"
os.environ["SPEECHMATICS_RT_URL"] = "wss://eu2.rt.speechmatics.com/v2"
os.environ["SPEECHMATICS_LANGUAGE"] = "en"
os.environ["SPEECHMATICS_EVENT_TYPES"] = "laughter"

async def test_single_prediction():
    # Initialize service
    settings = Settings.from_env()
    service = RealtimeLaughterService(settings)
    
    # Use a test audio file from the manifest
    test_audio_path = Path(r"d:\Projects\laughter-detection\datasets_unified\switchboard\clips\test\test_speech_000000.wav")
    if not test_audio_path.exists():
        print(f"Test audio file not found: {test_audio_path}")
        # List files in the directory to see what's available
        audio_dir = test_audio_path.parent
        if audio_dir.exists():
            print(f"Files in {audio_dir}:")
            for file in audio_dir.iterdir():
                if file.suffix == '.wav':
                    print(f"  {file.name}")
        return
    
    print(f"Testing Speechmatics API with file: {test_audio_path.name}")
    
    # Read audio file
    wav_bytes = test_audio_path.read_bytes()
    
    # Process audio
    try:
        result = await service.process_wav_bytes(
            wav_bytes=wav_bytes,
            source_name="test",
            chunk_ms=200,
            pace_realtime=False,
        )
        
        print("Success!")
        print(f"Session ID: {result['session_id']}")
        print(f"Event count: {len(result['events'])}")
        print(f"Events: {result['events']}")
        
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    asyncio.run(test_single_prediction())
