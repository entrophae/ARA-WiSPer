import base64
import pypdfium2 as pdfium
import re
from typing import BinaryIO, Any
from markitdown import MarkItDown, DocumentConverter, DocumentConverterResult

audioExt = ['.mp3', '.wav', '.m4a', '.opus']
imageExt = ['.png', '.jpg', '.jpeg', '.webp']
pdfExt = ['.pdf']

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
            "You are a strict multimodal extraction engine.\n\n"
            "DIRECTIVES BASED ON CONTENT:\n"
            "1. IF TEXT / TABLES / CHARTS: Transcribe all visible text verbatim using clean Markdown headers and tables.\n"
            "2. IF PORTRAIT / CHARACTER / OBJECT / SCENE: Output a strictly factual visual feature inventory. Categorize physically visible attributes using bullet points:\n"
            "   - Subject Type: (e.g., Character Portrait, House Exterior)\n"
            "   - Physical Features: (Hair color/length/style, eye color, facial features, skin tone, clothing)\n"
            "   - Colors & Lighting: (Exact visible palette, background color)\n"
            "   - Framing & Style: (e.g., Front-facing headshot, 2D digital illustration, solid background)\n\n"
            "STRICT NEGATIVE CONSTRAINTS:\n"
            "- NO EMOTIONAL OR PSYCHOLOGICAL EVALUATION: Never guess mood, feelings, or expressions (e.g., DO NOT write 'sad', 'pensive', 'somber', 'cheerful', 'reflective'). State only facial alignment (e.g., 'neutral mouth, eyes facing forward').\n"
            "- NO SPECULATION OR SYMBOLISM: Never guess narrative meaning, context, or use cases (e.g., DO NOT write 'symbolizes', 'could be used for').\n"
            "- NO META-LANGUAGE: Never use preamble phrases like 'The image is...', 'Description:', 'This illustration shows...', or 'Overall...'.\n"
            "- NO CODE FENCES: Do not wrap output in ``` markdown fences.\n"
            "- STRICT VISUAL ACCURACY: Describe ONLY physical colors and features visibly present. Do not guess or confuse colors."
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
            temperature=0.0,
            max_tokens=2048
        )

        print(f"VLM response done for {stream_info.file_name}")

        return DocumentConverterResult(markdown=f"### Image Content:\n\n{response.choices[0].message.content}")

class DocumentPipeline:
    def __init__(self, vlm_client, vlm_model, stt_client, stt_model):
        self.llm_client = vlm_client
        self.llm_model = vlm_model
        self.md = MarkItDown(llm_client=vlm_client, llm_model=vlm_model)
        if stt_client and stt_model:
            self.md.register_converter(AudioConverter(stt_client, stt_model))
        if vlm_client and vlm_model:
            self.md.register_converter(ImageConverter(vlm_client, vlm_model))

    def extract_text(self, file_path: str) -> str:
        print(f"Extracting text from {file_path}")
        result = self.md.convert(file_path)
        return result.text_content

    def chunk_text(self, text: str, file_name: str = "", strategy="markdown", add_context: bool = False) -> list[str]:
        print(f"Chunking down {file_name}")
        if strategy == "markdown":
            chunks = self._chunk_by_markdown_headers(text, file_name=file_name)
        elif strategy == "paragraphs":
            chunks = self._chunk_by_paragraphs(text)
        else:
            chunks = self._chunk_by_fixed_size(text)

        if add_context and self.llm_client and self.llm_model:
            print(f"Adding context to {file_name}")
            return self._enrich_chunks_with_context(full_text=text, chunks=chunks, file_name=file_name)
        return chunks

    def _chunk_by_fixed_size(self, text: str, chunk_size=1000, overlap=100) -> list[str]:
        chunks = []
        start = 0
        while start < len(text):
            end = start + chunk_size
            chunks.append(text[start:end])
            start += chunk_size - overlap
        return [c.strip() for c in chunks if c.strip()]

    def _chunk_by_markdown_headers(self, text: str, file_name: str = "") -> list[str]:
        raw_blocks = re.split(r'\n(?=#{1,2}\s)', text)
        merged_blocks = []
        current_block = ""

        for block in raw_blocks:
            block = block.strip()
            if not block:
                continue

            # Merge blocks under 400 characters into the next block
            if len(block) < 400:
                current_block = f"{current_block}\n\n{block}".strip()
            else:
                if current_block:
                    merged_blocks.append(f"{current_block}\n\n{block}".strip())
                    current_block = ""
                else:
                    merged_blocks.append(block)

        if current_block:
            merged_blocks.append(current_block)

        # Process oversized blocks
        final_chunks = []
        for block in merged_blocks:
            if len(block) > 1600:
                final_chunks.extend(self._chunk_by_paragraphs(block, max_size=1200))
            elif len(block) >= 50:  # Ignore remaining empty/micro artifacts
                final_chunks.append(block)

        return final_chunks

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

    def _enrich_chunks_with_context(self, full_text: str, chunks: list[str], file_name: str = "") -> list[str]:
        enriched_chunks = []
        doc_snippet = full_text[:3000]

        for chunk in chunks:
            prompt = (
                f"<document>\n{doc_snippet}\n</document>\n\n"
                f"Here is a chunk from the document:\n<chunk>\n{chunk}\n</chunk>\n\n"
                "Give a short, 1-sentence contextual description to situate this chunk within the document "
                "(e.g., mention the entity, document topic, or section). "
                "Output ONLY the concise contextual sentence without quotes or preamble."
            )
            try:
                response = self.llm_client.chat.completions.create(
                    model=self.llm_model,
                    messages=[{"role": "user", "content": prompt}],
                    max_tokens=60,
                    temperature=0.0
                )
                context_sentence = response.choices[0].message.content.strip()
                prefix = f"[Document: {file_name} | Context: {context_sentence}]\n" if file_name else f"[Context: {context_sentence}]\n"
                enriched_chunks.append(f"{prefix}{chunk}")
            except Exception as e:
                print(f"Context generation failed for chunk: {e}")
                enriched_chunks.append(chunk)

        return enriched_chunks