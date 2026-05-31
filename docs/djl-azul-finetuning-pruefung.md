# DJL / Azul Fine-Tuning-Prüfung — Ergebnis 31.05.2026

## Fragestellung
Kann Metis mittels DJL (Deep Java Library) oder Azul-Technologien
LLM-Fine-Tuning in pure Java durchführen?

## DJL (Deep Java Library) — AWS, Apache 2.0

### Stärken
- **LoRA Adapter Inference** ✅ DJL Serving unterstützt Multi-LoRA: ein Base-Model +
  mehrere LoRA-Adapter gleichzeitig (vLLM-Integration via `enable_lora=true`)
- **PyTorch/TensorFlow Bridge** — DJL kann PyTorch-Modelle in Java laden/inferieren
- **Transfer Learning API** — DJL hat `Model.fit()` und `Trainer` in Java für
  klassische ML-Modelle (nicht LLM)

### Schwächen für Fine-Tuning
- **Fine-Tuning ist Python-only** — DJL's LoRA-Fine-Tuning-Lösung basiert auf
  HuggingFace PEFT + Jupyter Notebooks (Python), nicht pure Java
- **Kein Java-basiertes LLM-Training** — die Java-API von DJL deckt nur
  Inference und klassisches ML-Training ab, kein Transformer-Training
- **SageMaker-Fokus** — DJL Serving ist für AWS-SageMaker-Deployment optimiert,
  nicht für On-Premise-Training

### Metis-Relevanz
- **Heute nutzbar:** DJL könnte Ollama für Inferenz ersetzen (via PyTorch-Backend
  und LMI-Container), bringt aber keine Vorteile gegenüber Ollama auf miniedi
- **Nicht nutzbar:** Fine-Tuning in pure Java ist mit DJL nicht möglich
- **Zukunft:** Wenn DJL ein Java-basiertes LLM-Training einführt, wäre es
  interessant — aktuell ist das nicht der Fall

## Azul Zulu / Prime

### Stärken
- **ReadyNow:** JVM-Warmup-Optimierung (Profil-Speicherung + Wiederverwendung
  von JIT-Kompilaten) — beschleunigt Neustarts massiv
- **Falcon JIT:** LLVM-basierter JIT-Compiler, höhere Peak-Performance
- **C4 GC:** Pauseless Garbage Collector für große Heaps (>100 GB)

### Kein LLM-Training
- Azul ist eine **JVM**, kein ML-Framework
- ReadyNow optimiert JIT-Kompilierung, nicht Modell-Training
- Keine Tensor-Operationen, kein Gradient-Descent, kein LoRA

### Metis-Relevanz
- **Heute nutzbar:** Zulu 25 LTS (bereits im Einsatz) + Prime für JVM-Performance
- **Nicht relevant für Fine-Tuning**

## Alternativen für Metis

### ✅ Bereits gebaut: Data Flywheel
- User-Korrekturen → Few-Shot-Export → OllamaPlanner-Prompt-Erweiterung
- Commit `aabaaf1` — funktioniert ohne Modell-Training
- Dies ist die *effektivste* Form von "Fine-Tuning" für Metis:
  Prompt-basiert, deterministisch, kein GPU-Training nötig

### ✅ Bereits gebaut: JLama POC (Review #1)
- Pure Java LLM Inference, lokale Modelle
- Kein Fine-Tuning, aber schließt die Ollama-Abhängigkeits-Lücke

### 🟡 Zukunft: Externes Fine-Tuning
- HuggingFace Transformers / PEFT / LoRA (Python) auf miniedi
- Modell-Export → GGUF → JLama lädt es
- Oder: Ollama Create API (`ollama create metis-finetuned -f Modelfile`)
- Metis kann diesen Prozess ORCHESTRIEREN, aber nicht selbst trainieren

## Fazit & Empfehlung

**Fine-Tuning in pure Java ist 2026 nicht praktikabel.** Weder DJL noch Azul
bieten Java-basierte LLM-Trainings-Frameworks. HuggingFace + PyTorch (Python)
sind alternativlos für echtes Fine-Tuning.

**Für Metis empfohlen:**
1. **Data Flywheel** als Primär-Mechanismus (Prompt-basiert, kein Training)
2. Bei Bedarf: **Ollama Create** mit GGUF-Export aus Python-Fine-Tuning
3. DJL nur als Inferenz-Backend falls Ollama jemals wegfällt
4. Azul Prime für JVM-Performance (C4 GC für große Heaps)

**Status Review #6: PRÜFUNG ABGESCHLOSSEN — kein fine-tuning Code**
