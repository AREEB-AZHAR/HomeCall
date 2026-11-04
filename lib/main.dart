import 'package:flutter/material.dart';
import 'app.dart';
import 'features/household.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final household = Household();
  runApp(HomeCall(household));
  household.initialize();
}
