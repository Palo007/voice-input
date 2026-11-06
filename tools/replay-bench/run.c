/* Replays 16 kHz mono WAV files through a Parakeet TDT model with the app's Sherpa-ONNX config
 * (feature dim 128, greedy search, 4 threads, see parakeet/ParakeetBackend.kt).
 * Usage: replay <model dir> a.wav [b.wav ...]  -> prints "file (seconds): [text]" per file.
 * Note: the app normalizes quiet input first (parakeet/InputLevel.kt); this runner does not,
 * so it shows what the raw model does with the audio. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "c-api.h"
int main(int argc, char **argv) {
  const char *d = argv[1];
  char enc[512], dec[512], joi[512], tok[512];
  snprintf(enc,512,"%s/encoder.int8.onnx",d); snprintf(dec,512,"%s/decoder.int8.onnx",d);
  snprintf(joi,512,"%s/joiner.int8.onnx",d); snprintf(tok,512,"%s/tokens.txt",d);
  SherpaOnnxOfflineRecognizerConfig c; memset(&c,0,sizeof(c));
  c.feat_config.sample_rate=16000; c.feat_config.feature_dim=128;
  c.model_config.transducer.encoder=enc; c.model_config.transducer.decoder=dec; c.model_config.transducer.joiner=joi;
  c.model_config.tokens=tok; c.model_config.num_threads=4; c.model_config.provider="cpu";
  c.model_config.model_type="nemo_transducer"; c.decoding_method="greedy_search"; c.max_active_paths=4;
  const SherpaOnnxOfflineRecognizer *r = SherpaOnnxCreateOfflineRecognizer(&c);
  if(!r){fprintf(stderr,"create failed\n");return 1;}
  for(int i=2;i<argc;i++){
    const SherpaOnnxWave *w = SherpaOnnxReadWave(argv[i]);
    const SherpaOnnxOfflineStream *s = SherpaOnnxCreateOfflineStream(r);
    SherpaOnnxAcceptWaveformOffline(s,w->sample_rate,w->samples,w->num_samples);
    SherpaOnnxDecodeOfflineStream(r,s);
    const SherpaOnnxOfflineRecognizerResult *res = SherpaOnnxGetOfflineStreamResult(s);
    printf("%s (%.1fs): [%s]\n", argv[i], w->num_samples/16000.0, res->text);
    fflush(stdout);
    SherpaOnnxDestroyOfflineRecognizerResult(res); SherpaOnnxDestroyOfflineStream(s); SherpaOnnxFreeWave(w);
  }
  return 0;
}
