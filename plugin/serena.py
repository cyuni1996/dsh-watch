"""Synthesize Serena speech using the desktop's local Qwen3-TTS model."""

import base64
import contextlib
import io
import json
import sys


def main():
    output = sys.stdout
    with contextlib.redirect_stdout(sys.stderr):
        import soundfile
        import torch
        from qwen_tts import Qwen3TTSModel

        cuda = torch.cuda.is_available()
        model = Qwen3TTSModel.from_pretrained(
            sys.argv[1],
            device_map="cuda:0" if cuda else "cpu",
            dtype=torch.bfloat16 if cuda else torch.float32,
            attn_implementation="sdpa",
            local_files_only=True,
        )
        for line in sys.stdin:
            try:
                text = json.loads(line)["text"]
                waves, rate = model.generate_custom_voice(
                    text=text, language="Auto", speaker="Serena", instruct=""
                )
                audio = io.BytesIO()
                soundfile.write(audio, waves[0], rate, format="WAV", subtype="PCM_16")
                result = {"audio": base64.b64encode(audio.getvalue()).decode("ascii")}
            except Exception as error:
                print(f"Synthesis failed: {type(error).__name__}", file=sys.stderr, flush=True)
                result = {"error": True}
            print(json.dumps(result), file=output, flush=True)


if __name__ == "__main__":
    main()
