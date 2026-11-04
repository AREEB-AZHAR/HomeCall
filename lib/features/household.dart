import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' as path;
import '../core/protocol.dart';
import 'media.dart';

class Peer {
  Peer(this.id, this.name, this.ip, {this.paired = false});
  final String id;
  String name, ip;
  bool paired;
  DateTime seen = DateTime.fromMillisecondsSinceEpoch(0);
  bool get online => DateTime.now().difference(seen).inSeconds < 55;
}

class Household extends ChangeNotifier {
  static const channel = MethodChannel('homecall/lan');
  late Database db;
  String id = '', name = '', notice = '', session = '';
  List<String> addresses = [];
  bool ready = false,
      available = false,
      outgoing = false,
      muted = false,
      speaker = false;
  final peers = <String, Peer>{}, inbox = Inbox();
  Peer? other;
  Phase phase = Phase.idle;
  Voice? voice;
  DateTime? connected;
  final lastInvite = <String, DateTime>{};
  Timer? deadline, poll;
  DateTime lastBeat = DateTime.now();
  bool refreshing = false;
  Future<void> queue = Future.value();
  final probing = <String>{};
  Future<void> initialize() async {
    try {
      id = (await channel.invokeMethod<String>('identity'))!;
      db = await openDatabase(
        path.join(await getDatabasesPath(), 'homecall.db'),
        version: 1,
        onCreate: (db, _) async {
          await db.execute('CREATE TABLE profile(name TEXT NOT NULL)');
          await db.execute(
            'CREATE TABLE peers(id TEXT PRIMARY KEY,name TEXT NOT NULL,ip TEXT NOT NULL)',
          );
        },
      );
      final rows = await db.query('profile');
      if (rows.isNotEmpty) name = rows.first['name'] as String;
      for (final r in await db.query('peers')) {
        final p = Peer(
          r['id'] as String,
          r['name'] as String,
          r['ip'] as String,
          paired: true,
        );
        peers[p.id] = p;
      }
      channel.setMethodCallHandler((c) async {
        final a = Map<String, dynamic>.from(c.arguments as Map);
        if (c.method == 'found') {
          unawaited(probe(a['ip']));
          return;
        }
        queue = queue
            .then((_) async {
              if (c.method == 'stopped') {
                poll?.cancel();
                available = false;
                notifyListeners();
              }
              if (c.method == 'message') {
                await receive(a);
              }
              if (c.method == 'notice') {
                notice = a['text'];
                notifyListeners();
              }
              if (a['session'] == session && phase.live) {
                if (c.method == 'disconnect') await hangup();
                if (c.method == 'route') {
                  speaker = a['speaker'] == 'true';
                  notifyListeners();
                }
                if (c.method == 'serviceError') fail(a['text']);
              }
              if (a['session'] == session &&
                  phase == Phase.ringing &&
                  !outgoing) {
                if (c.method == 'answer') await answer();
                if (c.method == 'decline') await hangup();
              }
            })
            .catchError((Object e) {
              fail(e);
            });
      });
      if (name.isNotEmpty) await start();
    } catch (e) {
      notice = e.toString();
    } finally {
      ready = true;
      notifyListeners();
    }
  }

  Future<void> profile(String value) async {
    value = value.trim();
    if (value.isEmpty || value.length > 40) return;
    await db.transaction((t) async {
      await t.delete('profile');
      await t.insert('profile', {'name': value});
    });
    name = value;
    await start();
    await channel.invokeMethod('permissions');
    notifyListeners();
  }

  Future<void> service(String mode) => channel.invokeMethod('service', {
    'mode': mode,
    'session': session,
    'name': other?.name ?? '',
  });
  Future<void> start() async {
    addresses = await channel.invokeListMethod<String>('addresses') ?? [];
    await channel.invokeMethod('start', {'name': name});
    await service('available');
    available = true;
    poll?.cancel();
    poll = Timer.periodic(const Duration(seconds: 15), (_) => refresh());
    await refresh();
  }

  Future<void> refresh() async {
    if (refreshing) return;
    refreshing = true;
    try {
      final ips = await channel.invokeListMethod<String>('addresses') ?? [];
      if (ips.join() != addresses.join()) {
        addresses = ips;
        await channel.invokeMethod('start', {'name': name});
      }
      peers.removeWhere((_, p) => !p.paired && !p.online);
      if (phase == Phase.connected) {
        if (DateTime.now().difference(lastBeat).inSeconds > 40) {
          finish(Phase.failed, 'Peer disconnected');
        } else {
          unawaited(send('HEARTBEAT'));
        }
      }
      for (final p in peers.values.toList()) {
        unawaited(probe(p.ip, expected: p.id));
      }
    } catch (e) {
      notice = e.toString();
    } finally {
      refreshing = false;
      notifyListeners();
    }
  }

  Future<Peer?> probe(
    String ip, {
    String? expected,
    bool manual = false,
  }) async {
    if (addresses.contains(ip) ||
        probing.contains(ip) ||
        probing.length >= 12) {
      return null;
    }
    probing.add(ip);
    try {
      final r = await channel.invokeMapMethod<String, String>('send', {
        'ip': ip,
        'expected': expected,
        'probe': true,
        'body': envelope('HELLO', id, expected ?? '*', '', {'name': name}),
      });
      return observe(r!['id']!, r['name']!, ip);
    } catch (e) {
      if (manual) {
        notice =
            'Could not reach $ip. Check Wi-Fi, pairing and client isolation.';
        notifyListeners();
      }
      return null;
    } finally {
      probing.remove(ip);
    }
  }

  Peer? observe(String key, String label, String ip) {
    if (!peers.containsKey(key) && peers.length >= 128) return null;
    final p = peers.putIfAbsent(key, () => Peer(key, label, ip));
    if (p.paired && (p.ip != ip || p.name != label)) {
      unawaited(
        db
            .update(
              'peers',
              {'ip': ip, 'name': label},
              where: 'id=?',
              whereArgs: [key],
            )
            .then<void>((_) {})
            .catchError((_) {}),
      );
    }
    p.ip = ip;
    p.name = label;
    p.seen = DateTime.now();
    notifyListeners();
    return p;
  }

  Future<void> trust(Peer p, bool value) async {
    if (value) {
      await db.insert('peers', {
        'id': p.id,
        'name': p.name,
        'ip': p.ip,
      }, conflictAlgorithm: ConflictAlgorithm.replace);
    } else {
      await db.delete('peers', where: 'id=?', whereArgs: [p.id]);
    }
    p.paired = value;
    notifyListeners();
  }

  Future<void> transmit(
    Peer p,
    String sid,
    String kind, [
    Map<String, dynamic> data = const {},
  ]) async {
    await channel.invokeMethod('send', {
      'ip': p.ip,
      'expected': p.id,
      'body': envelope(kind, id, p.id, sid, data),
    });
  }

  Future<void> send(String kind, [Map<String, dynamic> data = const {}]) async {
    final sid = session;
    try {
      await transmit(other!, sid, kind, data);
    } catch (e) {
      if (sid == session && phase.live) fail(e);
    }
  }

  void timer(int seconds) {
    deadline?.cancel();
    deadline = Timer(Duration(seconds: seconds), () {
      if (phase.live) {
        unawaited(send('CALL_END'));
        finish(
          Phase.timedOut,
          'Peer did not connect. Check local network isolation.',
        );
      }
    });
  }

  void begin(Peer p, String sid, bool caller) {
    other = p;
    session = sid;
    outgoing = caller;
    phase = caller ? Phase.inviting : Phase.ringing;
    connected = null;
    muted = false;
    speaker = false;
    voice = Voice();
    notice = '';
    timer(30);
    notifyListeners();
  }

  Future<void> call(Peer p) async {
    if (phase.live || !p.paired) return;
    begin(p, token(), true);
    final sid = session;
    try {
      await admit(false);
      if (session == sid && phase.live) await send('CALL_INVITE');
    } catch (e) {
      if (session == sid && phase.live) fail(e);
    }
  }

  Future<void> admit(bool incoming) => channel.invokeMethod('telecom', {
    'incoming': incoming,
    'name': other!.name,
    'session': session,
  });

  Future<void> answer() async {
    if (phase != Phase.ringing || outgoing) return;
    final sid = session;
    phase = Phase.connecting;
    timer(35);
    notifyListeners();
    try {
      await openMedia();
      if (session == sid && phase.live) await send('CALL_ACCEPT');
    } catch (e) {
      if (session == sid) fail(e);
    }
  }

  Future<void> openMedia() async {
    final current = voice!;
    await service('active');
    if (current != voice) return;
    await current.open(
      (c) {
        if (current == voice) unawaited(send('ICE_CANDIDATE', c));
      },
      (s) {
        if (current != voice) return;
        if (s == RTCPeerConnectionState.RTCPeerConnectionStateConnected) {
          phase = Phase.connected;
          deadline?.cancel();
          connected ??= DateTime.now();
          lastBeat = DateTime.now();
          notifyListeners();
        } else if (s == RTCPeerConnectionState.RTCPeerConnectionStateFailed) {
          fail('No reachable audio path. Check hotspot or router isolation.');
        }
      },
    );
  }

  Future<void> receive(Map<String, dynamic> a) async {
    final key = a['id'] as String, ip = a['ip'] as String;
    final m = inbox.read(a['body'], key, id, peers[key]?.paired ?? false);
    if (m == null) return;
    final k = m['kind'],
        sid = m['session'],
        d = m['data'] as Map<String, dynamic>;
    if (k == 'HELLO') {
      observe(key, d['name'], ip);
      return;
    }
    final p = peers[key]!;
    p.seen = DateTime.now();
    p.ip = ip;
    if (k == 'CALL_INVITE') {
      if (session == sid) return;
      if (phase.live &&
          !yieldInvite(id, key, phase, outgoing, other?.id == key)) {
        await transmit(p, sid, 'CALL_REJECT', {'reason': 'busy'});
        return;
      }
      if (DateTime.now()
              .difference(lastInvite[key] ?? DateTime(2000))
              .inSeconds <
          8) {
        await transmit(p, sid, 'CALL_REJECT');
        return;
      }
      if (phase.live) finish(Phase.cancelled, '');
      lastInvite[key] = DateTime.now();
      begin(p, sid, false);
      await admit(true);
      if (session == sid && phase == Phase.ringing) await send('CALL_RINGING');
      return;
    }
    if (!phase.live || other?.id != key || session != sid) return;
    final current = voice!;
    switch (k) {
      case 'CALL_RINGING':
        if (outgoing && phase == Phase.inviting) phase = Phase.ringing;
      case 'CALL_ACCEPT':
        if (!outgoing || !(phase == Phase.ringing || phase == Phase.inviting)) {
          return;
        }
        phase = Phase.connecting;
        timer(35);
        notifyListeners();
        await openMedia();
        if (voice != current) return;
        final sdp = await current.describe(true);
        if (voice == current) await send('SDP_OFFER', {'sdp': sdp});
      case 'SDP_OFFER':
        if (outgoing || phase != Phase.connecting || current.remoteSet) return;
        await current.remote(d['sdp'], 'offer');
        final sdp = await current.describe(false);
        if (voice == current) await send('SDP_ANSWER', {'sdp': sdp});
      case 'SDP_ANSWER':
        if (outgoing && phase == Phase.connecting && !current.remoteSet) {
          await current.remote(d['sdp'], 'answer');
        }
      case 'ICE_CANDIDATE':
        await current.ice(d);
      case 'CALL_REJECT':
        finish(d['reason'] == 'busy' ? Phase.busy : Phase.rejected, '');
      case 'CALL_CANCEL':
        finish(Phase.cancelled, '');
      case 'CALL_END':
        finish(Phase.ended, '');
      case 'HEARTBEAT':
        lastBeat = DateTime.now();
      case 'ERROR':
        finish(Phase.failed, 'Other phone could not continue the call.');
    }
    notifyListeners();
  }

  Future<void> hangup() async {
    if (!phase.live) return;
    final ring = phase == Phase.inviting || phase == Phase.ringing;
    final sending = send(
      ring ? (outgoing ? 'CALL_CANCEL' : 'CALL_REJECT') : 'CALL_END',
    );
    finish(
      ring ? (outgoing ? Phase.cancelled : Phase.rejected) : Phase.ended,
      '',
    );
    await sending;
  }

  void fail(Object e) {
    if (phase.live) {
      unawaited(transmit(other!, session, 'ERROR').catchError((_) {}));
      finish(Phase.failed, e.toString());
    } else {
      notice = e.toString();
      notifyListeners();
    }
  }

  void finish(Phase end, String text) {
    deadline?.cancel();
    phase = end;
    notice = text;
    final old = voice;
    voice = null;
    unawaited(old?.close().catchError((_) {}));
    unawaited(
      channel
          .invokeMethod<void>('endTelecom', {
            'session': session,
            'reason': end.name,
          })
          .catchError((_) {}),
    );
    unawaited(service('available').catchError((_) {}));
    notifyListeners();
  }

  void toggleMute() {
    muted = !muted;
    voice?.mute(muted);
    notifyListeners();
  }

  Future<void> toggleSpeaker() async {
    await channel.invokeMethod('speaker', {'value': !speaker});
    notifyListeners();
  }
}
