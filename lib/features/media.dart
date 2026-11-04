import 'package:flutter_webrtc/flutter_webrtc.dart';

class Voice {
  RTCPeerConnection? pc;
  MediaStream? stream;
  bool remoteSet = false, closed = false;
  final early = <RTCIceCandidate>[];
  Future<void> open(
    void Function(Map<String, dynamic>) candidate,
    void Function(RTCPeerConnectionState) state,
  ) async {
    await Helper.setAndroidAudioConfiguration(
      AndroidAudioConfiguration(manageAudioFocus: false),
    );
    final p = await createPeerConnection({
      'iceServers': [],
      'sdpSemantics': 'unified-plan',
    });
    if (closed) {
      await p.close();
      await p.dispose();
      return;
    }
    pc = p;
    p.onConnectionState = state;
    p.onIceCandidate = (c) {
      if (!closed && (c.candidate?.contains(' typ host') ?? false)) {
        candidate(c.toMap());
      }
    };
    final audio = await navigator.mediaDevices.getUserMedia({
      'audio': {'echoCancellation': true, 'noiseSuppression': true},
      'video': false,
    });
    if (closed) {
      for (final t in audio.getTracks()) {
        await t.stop();
      }
      await audio.dispose();
      return;
    }
    stream = audio;
    for (final t in audio.getAudioTracks()) {
      await p.addTrack(t, audio);
    }
    final caps = await getRtpSenderCapabilities('audio');
    final opus =
        caps.codecs
            ?.where((c) => c.mimeType.toLowerCase() == 'audio/opus')
            .toList() ??
        [];
    if (opus.isEmpty) {
      throw StateError('Opus audio is unavailable on this phone.');
    }
    for (final transceiver in await p.getTransceivers()) {
      await transceiver.setCodecPreferences(opus);
    }
  }

  Future<String> describe(bool offer) async {
    final p = pc!;
    final d = offer ? await p.createOffer() : await p.createAnswer();
    await p.setLocalDescription(d);
    return d.sdp!;
  }

  Future<void> remote(String sdp, String type) async {
    await pc!.setRemoteDescription(RTCSessionDescription(sdp, type));
    remoteSet = true;
    for (final c in early) {
      await pc!.addCandidate(c);
    }
    early.clear();
  }

  Future<void> ice(Map<String, dynamic> d) async {
    final c = RTCIceCandidate(d['candidate'], d['sdpMid'], d['sdpMLineIndex']);
    if (remoteSet) {
      await pc!.addCandidate(c);
    } else if (early.length < 64) {
      early.add(c);
    }
  }

  void mute(bool value) {
    for (final t in stream?.getAudioTracks() ?? <MediaStreamTrack>[]) {
      t.enabled = !value;
    }
  }

  Future<void> close() async {
    closed = true;
    early.clear();
    try {
      for (final t in stream?.getTracks() ?? <MediaStreamTrack>[]) {
        await t.stop();
      }
    } finally {
      try {
        await stream?.dispose();
      } finally {
        try {
          await pc?.close();
        } finally {
          await pc?.dispose();
        }
      }
    }
  }
}
