// Host acceptance test for the actual patched native engine. See docs/pocket-tts-paul.md.
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
  double first_ms = -1, total_ms = 0;
  std::vector<float> pcm;
  int calls = 0;
  int first_frames = 0;
  bool cancel = false;
};
static int32_t capture(const float *p, int32_t n, float progress, void *arg) {
  auto &c = *static_cast<Capture *>(arg);
  if (c.calls++ == 0) {
    c.first_ms = std::chrono::duration<double, std::milli>(Clock::now() - c.start).count();
    c.first_frames = n;
  }
  check(n > 0 && progress >= 0 && progress <= 1, "Invalid callback");
  check(std::all_of(p, p+n, [](float v) { return std::isfinite(v); }), "Nonfinite PCM");
  c.pcm.insert(c.pcm.end(), p, p+n);
  return c.cancel ? 0 : 1;
}
int main(int argc, char **argv) {
  check(argc == 3, "Usage: check_pocket_stream MODEL_DIR PAUL_WAV");
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
  auto run = [&](const std::string &text, const std::string &session, bool cancel=false) {
    const std::string extra = "{\"seed\":42,\"temperature\":0.7,\"max_reference_audio_len\":15,"
        "\"min_char_in_sentence\":240,\"max_char_in_sentence\":240,"
        "\"first_chunk_size\":3,\"chunk_size\":5,\"jarvis_session\":\"" + session + "\"}";
    gen.extra = extra.c_str();
    Capture c; c.cancel=cancel;
    const auto *audio = SherpaOnnxOfflineTtsGenerateWithConfig(tts,text.c_str(),&gen,capture,&c);
    c.total_ms = std::chrono::duration<double, std::milli>(Clock::now() - c.start).count();
    check(audio && audio->sample_rate == 24000, "Missing generated audio");
    check(c.pcm.size() == static_cast<size_t>(audio->n), "Missing or duplicated callback PCM");
    check(std::equal(c.pcm.begin(), c.pcm.end(), audio->samples), "Callback/result mismatch");
    check(c.calls > 0 && (!cancel || c.calls == 1), "Cancellation ignored");
    // A completed EOS state can yield nonempty but truncated output on the NEXT
    // sentence. Reject implausibly short speech, not just silent/empty buffers.
    const auto words = 1 + std::count(text.begin(), text.end(), ' ');
    check(cancel || c.pcm.size() >= words * 24000 / 8, "Sentence was truncated after carrying EOS state");
    check(std::any_of(c.pcm.begin(),c.pcm.end(),[](float v){return std::abs(v)>0.01f;}), "Silent output");
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    std::cout << "session=" << session << " first_ms=" << c.first_ms << " total_ms=" << c.total_ms
              << " first_frames=" << c.first_frames << " callbacks=" << c.calls
              << " seconds=" << c.pcm.size()/24000.0 << std::endl;
    return c;
  };
  const std::string first = "Kiko stood on the deck as the moon rose above the island.";
  const std::string second = "The little monkey had found a map inside an old brass compass, and tonight his crew would follow it.";
  auto baseline = run(first, "");
  auto a = run(first, "answer-a");
  auto b = run(second, "answer-a");
  auto fresh = run(second, "fresh");
  check(b.pcm != fresh.pcm, "Decoder/sampling continuation was lost");
  auto again = run(first, "answer-b");
  // Isolated filler generation must not reset or contaminate answer-b's state.
  run("Um, one second.", "");
  auto continued = run(second, "answer-b");
  check(a.pcm == again.pcm && b.pcm == continued.pcm, "Session leaked state or filler altered it");
  check(a.first_frames == 5760, "Expected early three-frame PCM, not full-sentence buffering");
  check(a.first_ms < baseline.first_ms, "Native streaming did not improve onset in host comparison");
  run(first, "cancelled", true);
  auto recovered = run(first, "cancelled");
  check(a.pcm == recovered.pcm, "Cancelled state was reused");
  std::vector<float> story = a.pcm;
  auto second_recovered = run(second, "cancelled");
  check(second_recovered.pcm == b.pcm, "Recovered continuation differs");
  story.insert(story.end(), second_recovered.pcm.begin(), second_recovered.pcm.end());
  // Continue longer to catch KV/decoder shape or lifetime failures after several sentences.
  for (const auto &sentence : {"Beyond the reef, a blue light flickered beneath the waves.",
      "Kiko lowered a lantern and discovered the roof of a sunken library.",
      "Its windows were still glowing.",
      "He smiled, tied a rope around his waist, and handed the other end to his first mate.",
      "Gold could wait.",
      "Somewhere below them was a story that no pirate had ever heard, and Kiko intended to bring it home."}) {
    auto next = run(sentence, "cancelled");
    story.insert(story.end(), next.pcm.begin(), next.pcm.end());
  }
  auto joined = run(first + " " + second, "ready-sentences");
  check(joined.first_frames == 5760, "Ready sentences were fully buffered");
  const std::string full_story = first + " " + second +
      " Beyond the reef, a blue light flickered beneath the waves."
      " Kiko lowered a lantern and discovered the roof of a sunken library. Its windows were still glowing."
      " He smiled, tied a rope around his waist, and handed the other end to his first mate. Gold could wait."
      " Somewhere below them was a story that no pirate had ever heard, and Kiko intended to bring it home.";
  auto whole = run(full_story, "whole-story");
  check(whole.first_frames == 5760, "Full ready text delayed native PCM");
  SherpaOnnxWriteWave(story.data(), story.size(), 24000, "pocket-stream-check.wav");
  SherpaOnnxFreeWave(voice);
  SherpaOnnxDestroyOfflineTts(tts);
  std::cout << "PASS: early PCM, exact callback delivery, persistent state, filler isolation, deterministic new sessions and cancellation." << std::endl;
}
