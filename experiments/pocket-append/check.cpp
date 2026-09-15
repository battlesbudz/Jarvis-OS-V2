// Host-only appendability probe. See README.md; not a live-call implementation.
#include "sherpa-onnx/c-api/c-api.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
using Clock = std::chrono::steady_clock;
static void check(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
struct Capture {
  Clock::time_point start = Clock::now();
  double first_ms = -1;
  std::vector<float> pcm;
  int calls = 0;
};
static int32_t capture(const float *p, int32_t n, float progress, void *arg) {
  auto &c = *static_cast<Capture *>(arg);
  if (c.calls++ == 0) {
    c.first_ms = std::chrono::duration<double, std::milli>(Clock::now() - c.start).count();
  }
  check(n > 0 && progress >= 0 && progress <= 1, "Invalid callback");
  check(std::all_of(p, p+n, [](float v) { return std::isfinite(v); }), "Nonfinite PCM");
  c.pcm.insert(c.pcm.end(), p, p+n);
  return 1;
}
int main(int argc, char **argv) {
  check(argc == 3, "Usage: jarvis-pocket-append-check MODEL_DIR PAUL_WAV");
  std::string dir = argv[1];
  const std::vector<std::string> paths = {dir+"/lm_flow.int8.onnx", dir+"/lm_main.int8.onnx",
      dir+"/encoder.onnx", dir+"/decoder.int8.onnx", dir+"/text_conditioner.onnx",
      dir+"/vocab.json", dir+"/token_scores.json"};
  SherpaOnnxOfflineTtsConfig cfg{};
  auto &m = cfg.model.pocket;
  m.lm_flow=paths[0].c_str(); m.lm_main=paths[1].c_str(); m.encoder=paths[2].c_str();
  m.decoder=paths[3].c_str(); m.text_conditioner=paths[4].c_str();
  m.vocab_json=paths[5].c_str(); m.token_scores_json=paths[6].c_str();
  m.voice_embedding_cache_capacity=1;
  cfg.model.num_threads=2; cfg.model.provider="cpu"; cfg.max_num_sentences=1;
  cfg.silence_scale=1;
  const auto *tts = SherpaOnnxCreateOfflineTts(&cfg);
  check(tts != nullptr, "Model creation failed");
  const auto *voice = SherpaOnnxReadWave(argv[2]);
  check(voice != nullptr, "Reference WAV failed");
  SherpaOnnxGenerationConfig gen{};
  gen.reference_audio=voice->samples; gen.reference_audio_len=voice->num_samples;
  gen.reference_sample_rate=voice->sample_rate; gen.num_steps=5;
  gen.silence_scale=1; gen.speed=1;

  // Suffix is withheld from LM conditioning until the requested latent step.
  const std::string prefix = "Good evening, sir.";
  const std::string suffix = " Your next appointment begins in twenty minutes. There is time for a cup of tea.";
  for (int step : {-2, -1, 0, 5, 15, 30, 100, 105, -4}) {
    bool words = step >= 100;
    if (words) step -= 100;
    std::string label = step == -2 ? "prefix" : step == -1 ? "whole" : "append-"+std::to_string(step);
    if (step == -4) label = "whole-repeat";
    if (words) label = "words-"+std::to_string(step);
    std::string text = (step == -1 || step == -4) ? prefix+suffix : prefix;
    std::string extra = "{\"seed\":42,\"temperature\":0.7,\"max_reference_audio_len\":15,"
      "\"first_chunk_size\":3,\"chunk_size\":5,\"max_frames\":400,\"jarvis_session\":\""+label+"\"";
    if (step >= 0) extra += ",\"experiment_append_step\":"+std::to_string(step)+
      ",\"experiment_append_text\":\""+suffix+"\"";
    if (words) extra += ",\"experiment_word_interval\":1";
    extra += "}";
    gen.extra=extra.c_str();
    Capture c;
    const auto *audio=SherpaOnnxOfflineTtsGenerateWithConfig(tts,text.c_str(),&gen,capture,&c);
    check(audio && audio->sample_rate==24000, "No output");
    check(c.pcm.size()==static_cast<size_t>(audio->n), "Callback frame mismatch");
    check(std::equal(c.pcm.begin(),c.pcm.end(),audio->samples), "Callback PCM mismatch");
    std::string filename=label+".wav";
    SherpaOnnxWriteWave(c.pcm.data(),c.pcm.size(),24000,filename.c_str());
    std::cout << "case=" << label << " frames=" << c.pcm.size()
              << " seconds=" << c.pcm.size()/24000.0 << " callbacks=" << c.calls
              << " first_pcm_ms=" << c.first_ms << std::endl;
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
  }
  SherpaOnnxFreeWave(voice);
  SherpaOnnxDestroyOfflineTts(tts);
}
