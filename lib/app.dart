import 'dart:async';
import 'package:flutter/material.dart';
import 'core/protocol.dart';
import 'features/household.dart';

class HomeCall extends StatelessWidget {
  const HomeCall(this.model, {super.key});
  final Household model;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'HomeCall',
    debugShowCheckedModeBanner: false,
    theme: theme(Brightness.light),
    darkTheme: theme(Brightness.dark),
    home: Dashboard(model),
  );
  ThemeData theme(Brightness brightness) => ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(
      seedColor: const Color(0xff006a68),
      brightness: brightness,
    ),
  );
}

class Dashboard extends StatefulWidget {
  const Dashboard(this.h, {super.key});
  final Household h;
  @override
  State<Dashboard> createState() => _DashboardState();
}

class _DashboardState extends State<Dashboard> {
  Household get h => widget.h;
  bool settings = false;
  final name = TextEditingController();
  Timer? tick;
  @override
  void initState() {
    super.initState();
    tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (h.phase == Phase.connected) setState(() {});
    });
  }

  @override
  void dispose() {
    tick?.cancel();
    name.dispose();
    super.dispose();
  }

  Future<void> run(Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    }
  }

  Future<String?> input(String title, String label, String value) async {
    var draft = value;
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextFormField(
          initialValue: value,
          autofocus: true,
          maxLength: 40,
          decoration: InputDecoration(labelText: label),
          onChanged: (v) => draft = v,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, draft.trim()),
            child: const Text('Continue'),
          ),
        ],
      ),
    );
  }

  String fingerprint(String id) => RegExp('.{1,4}')
      .allMatches(id.substring(0, 16).toUpperCase())
      .map((m) => m.group(0))
      .join(' ');
  Future<void> pair(Peer p) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Verify ${p.name}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Open Settings on the other phone. Compare its identity code with this one.',
            ),
            const SizedBox(height: 16),
            SelectableText(
              fingerprint(p.id),
              style: Theme.of(ctx).textTheme.titleLarge,
            ),
            const SizedBox(height: 16),
            const Text('Repeat on the other phone so both can call.'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Codes match'),
          ),
        ],
      ),
    );
    if (ok == true) await h.trust(p, true);
  }

  Widget heading(String text) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Text(text, style: Theme.of(context).textTheme.titleLarge),
  );
  Widget hero(String label, String title, String detail, IconData icon) {
    final c = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: c.primaryContainer,
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: c.onPrimaryContainer),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  label.toUpperCase(),
                  style: TextStyle(
                    letterSpacing: 1.6,
                    color: c.onPrimaryContainer,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Text(
            title,
            style: Theme.of(
              context,
            ).textTheme.headlineMedium?.copyWith(color: c.onPrimaryContainer),
          ),
          const SizedBox(height: 8),
          Text(detail),
        ],
      ),
    );
  }

  List<Widget> onboarding() => [
    hero(
      'Household voice',
      'Call across your Wi-Fi',
      'Connect both phones to the same router or hotspot. HomeCall carries your voice directly between them.',
      Icons.home_outlined,
    ),
    heading('Name this device'),
    TextField(
      controller: name,
      maxLength: 40,
      decoration: const InputDecoration(
        border: OutlineInputBorder(),
        labelText: 'Display name',
        hintText: 'Kitchen phone',
      ),
    ),
    const Text(
      'Allow microphone access for calls and notifications for incoming rings.',
    ),
    const SizedBox(height: 16),
    FilledButton(
      onPressed: () => run(() => h.profile(name.text)),
      child: const Text('Set up HomeCall'),
    ),
  ];
  List<Widget> home() => [
    hero(
      h.addresses.isEmpty ? 'Waiting for a network' : 'Local network',
      h.name,
      h.addresses.isEmpty
          ? 'Join Wi-Fi or start a hotspot.'
          : h.addresses.join('  ·  '),
      Icons.wifi,
    ),
    heading('Household devices'),
    if (h.peers.isEmpty)
      const ListTile(
        leading: Icon(Icons.radar),
        title: Text('Looking for nearby phones'),
        subtitle: Text(
          'Open HomeCall on the other phone. You can also enter its local IP.',
        ),
      ),
    for (final p in h.peers.values)
      Card(
        child: ListTile(
          leading: const CircleAvatar(child: Icon(Icons.smartphone)),
          title: Text(p.name),
          subtitle: Text(
            '${p.online ? "Online" : "Offline"} · ${p.paired ? "Paired" : "Unverified"}',
          ),
          trailing: p.paired
              ? IconButton(
                  tooltip: 'Call ${p.name}',
                  onPressed: p.online ? () => run(() => h.call(p)) : null,
                  icon: const Icon(Icons.call),
                )
              : TextButton(
                  onPressed: () => run(() => pair(p)),
                  child: const Text('Pair'),
                ),
        ),
      ),
    const SizedBox(height: 12),
    OutlinedButton.icon(
      icon: const Icon(Icons.add_link),
      label: const Text('Pair by local IP'),
      onPressed: () => run(() async {
        final ip = await input('Find a phone', 'Local IPv4 address', '');
        if (ip != null) {
          final p = await h.probe(ip, manual: true);
          if (mounted && p != null && !p.paired) await pair(p);
        }
      }),
    ),
    if (h.phase != Phase.idle)
      ListTile(
        title: Text('Last call · ${h.phase.name}'),
        subtitle: Text(h.other?.name ?? ''),
      ),
  ];
  List<Widget> prefs() => [
    hero(
      'Device identity',
      h.name,
      fingerprint(h.id),
      Icons.verified_user_outlined,
    ),
    ListTile(
      title: const Text('Change display name'),
      trailing: const Icon(Icons.edit),
      onTap: () => run(() async {
        final v = await input('Rename this phone', 'Display name', h.name);
        if (v != null) await h.profile(v);
      }),
    ),
    ListTile(
      title: const Text('Microphone and notifications'),
      subtitle: const Text('Review runtime permissions'),
      onTap: () => run(() => Household.channel.invokeMethod('permissions')),
    ),
    ListTile(
      title: const Text('Local listener'),
      subtitle: Text(
        '${h.available ? "Started" : "Unavailable"} · TCP 43871\n${h.addresses.join(", ")}',
      ),
    ),
    const ListTile(
      title: Text('Call audio'),
      subtitle: Text('Earpiece by default. Switch to speaker during a call.'),
    ),
    heading('Paired devices'),
    for (final p in h.peers.values.where((p) => p.paired))
      ListTile(
        title: Text(p.name),
        trailing: TextButton(
          onPressed: () => run(() => h.trust(p, false)),
          child: const Text('Forget'),
        ),
      ),
    const ListTile(
      title: Text('HomeCall'),
      subtitle: Text('Version 1.0.0 · Local voice'),
    ),
  ];
  List<Widget> callScreen() {
    final incoming = h.phase == Phase.ringing && !h.outgoing;
    final elapsed = h.connected == null
        ? 0
        : DateTime.now().difference(h.connected!).inSeconds;
    return [
      hero(
        incoming ? 'Incoming call' : 'Household call',
        h.other!.name,
        h.phase == Phase.connected
            ? '${elapsed ~/ 60}:${(elapsed % 60).toString().padLeft(2, "0")}'
            : h.phase.name,
        Icons.graphic_eq,
      ),
      const SizedBox(height: 30),
      if (incoming)
        FilledButton.icon(
          onPressed: () => run(h.answer),
          icon: const Icon(Icons.call),
          label: const Text('Answer'),
        ),
      if (h.phase == Phase.connected)
        Wrap(
          alignment: WrapAlignment.center,
          spacing: 12,
          children: [
            FilterChip(
              label: const Text('Mute'),
              selected: h.muted,
              onSelected: (_) => h.toggleMute(),
            ),
            FilterChip(
              label: const Text('Speaker'),
              selected: h.speaker,
              onSelected: (_) => run(h.toggleSpeaker),
            ),
          ],
        ),
      const SizedBox(height: 16),
      FilledButton.tonalIcon(
        onPressed: () => run(h.hangup),
        icon: const Icon(Icons.call_end),
        label: Text(incoming ? 'Decline' : 'End call'),
      ),
    ];
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: h,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: Text(settings && !h.phase.live ? 'Settings' : 'HomeCall'),
        leading: settings && !h.phase.live
            ? BackButton(onPressed: () => setState(() => settings = false))
            : null,
        actions: [
          if (h.name.isNotEmpty && !h.phase.live && !settings) ...[
            IconButton(
              tooltip: 'Refresh',
              onPressed: () => run(h.start),
              icon: const Icon(Icons.refresh),
            ),
            IconButton(
              tooltip: 'Settings',
              onPressed: () => setState(() => settings = true),
              icon: const Icon(Icons.settings_outlined),
            ),
          ],
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 600),
            child: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                if (!h.ready)
                  const LinearProgressIndicator()
                else if (h.phase.live)
                  ...callScreen()
                else if (h.name.isEmpty)
                  ...onboarding()
                else if (settings)
                  ...prefs()
                else
                  ...home(),
                if (h.notice.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Text(h.notice),
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
