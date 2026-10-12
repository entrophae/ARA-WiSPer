import base64
import re
from typing import BinaryIO, Any
from markitdown import MarkItDown, DocumentConverter, DocumentConverterResult

audioExt = ['.mp3', '.wav', '.m4a', '.opus']
imageExt = ['.png', '.jpg', '.jpeg', '.webp']

class AudioConverter(DocumentConverter):
    def __init__(self, audio_client, audio_model):
        super().__init__()
        self.client = audio_client
        self.audio_model = audio_model

    def accepts(self, file_stream: BinaryIO, stream_info: Any, **kwargs: Any) -> bool:
        return (stream_info.extension or "").lower() in audioExt

    def convert(self, file_stream: BinaryIO, stream_info: Any, **kwargs: Any) -> DocumentConverterResult:
        print(f"Routing audio to Faster-Whisper ({self.audio_model})...")
        try:
            transcript = self.client.audio.transcriptions.create(
                model=self.audio_model,
                file=(f"audio{stream_info.extension}", file_stream)
            )
            return DocumentConverterResult(markdown=f"### Audio Transcript:\n\n{transcript.text}")
        except Exception as e:
            print(f"Audio transcription failed: {e}")
            return DocumentConverterResult(markdown="### Audio Transcript:\n\n[Transcription Failed]")

class ImageConverter(DocumentConverter):
    def __init__(self, vlm_client, vlm_model):
        super().__init__()
        self.client = vlm_client
        self.vlm_model = vlm_model

    def accepts(self, file_stream: BinaryIO, stream_info: Any, **kwargs: Any) -> bool:
        return (stream_info.extension or "").lower() in imageExt

    def convert(self, file_stream: BinaryIO, stream_info: Any, **kwargs: Any) -> DocumentConverterResult:
        print(f"Routing image to VLM ({self.vlm_model})...")
        base64_image = base64.b64encode(file_stream.read()).decode('utf-8')
        ext = (stream_info.extension or "").lower().replace('.', '')
        mime_type = f"image/{'jpeg' if ext in ['jpg', 'jpeg'] else ext}"

        prompt = (
            "Analyze this image and apply the correct mode based on its content:\n"
            "1. IF IT CONTAINS TEXT, HANDWRITING, EQUATIONS, OR FORMULAS: Act as a strict OCR and mathematical extraction engine. Output the exact text, numbers, and formulas verbatim. Do NOT summarize, do NOT describe the layout, and never use phrases like 'The image shows'. Treat all handwriting as literal text to be output character-by-character.\n"
            "2. IF IT CONTAINS TABLES: Transcribe them strictly using Markdown table syntax (| Header | Header |), ensuring rows and columns map cleanly.\n"
            "3. IF IT CONTAINS CHARTS OR DIAGRAMS WITH DATA: Extract the axes labels, data points, or key values into a structured bulleted list or markdown table.\n"
            "4. IF IT IS ENTIRELY TEXTLESS (such as a pure photo, illustration, or graphic without text): Switch to descriptive mode and provide a rich, detailed semantic summary covering its visual elements, objects, relationships, and context.\n"
            "CRITICAL: Follow these rules strictly and output only the raw content without conversational meta-language."
        )

        response = self.client.chat.completions.create(
            model=self.vlm_model,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": f"data:{mime_type};base64,{base64_image}"}}
                    ]
                }
            ],
            max_tokens=2048
        )
        print(f"VLM response done");
        return DocumentConverterResult(markdown=f"### Image Content:\n\n{response.choices[0].message.content}")

class DocumentPipeline:
    def __init__(self, vlm_client, vlm_model, stt_client, stt_model):
        self.md = MarkItDown(llm_client=vlm_client, llm_model=vlm_model)
        if stt_client and stt_model:
            self.md.register_converter(AudioConverter(stt_client, stt_model))
        if vlm_client and vlm_model:
            self.md.register_converter(ImageConverter(vlm_client, vlm_model))

    def extract_text(self, file_path: str) -> str:
        result = self.md.convert(file_path)
        return result.text_content

    def chunk_text(self, text: str, strategy="markdown") -> list[str]:
        if strategy == "markdown":
            return self._chunk_by_markdown_headers(text)
        elif strategy == "paragraphs":
            return self._chunk_by_paragraphs(text)
        else:
            return self._chunk_by_fixed_size(text)

    def _chunk_by_fixed_size(self, text: str, chunk_size=1000, overlap=100) -> list[str]:
        chunks = []
        start = 0
        while start < len(text):
            end = start + chunk_size
            chunks.append(text[start:end])
            start += chunk_size - overlap
        return [c.strip() for c in chunks if c.strip()]

    def _chunk_by_markdown_headers(self, text: str) -> list[str]:
        raw_blocks = re.split(r'\n(?=#{1,3}\s)', text)
        chunks = []
        for block in raw_blocks:
            block = block.strip()
            if block:
                if len(block) > 1500:
                    chunks.extend(self._chunk_by_paragraphs(block, max_size=1200))
                else:
                    chunks.append(block)
        return chunks

    def _chunk_by_paragraphs(self, text: str, max_size=1200) -> list[str]:
        paragraphs = text.split('\n\n')
        chunks = []
        current_chunk = ""
        for p in paragraphs:
            p = p.strip()
            if not p:
                continue
            if len(current_chunk) + len(p) + 2 < max_size:
                current_chunk = f"{current_chunk}\n\n{p}" if current_chunk else p
            else:
                if current_chunk:
                    chunks.append(current_chunk)
                current_chunk = p
        if current_chunk:
            chunks.append(current_chunk)
        return chunks