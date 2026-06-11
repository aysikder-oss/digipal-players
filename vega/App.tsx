import React, { useState, useEffect, Component } from 'react';
import { StatusBar, View, StyleSheet, ActivityIndicator, Text } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import ServerSetupScreen, { PairedInfo } from './src/screens/ServerSetupScreen';
import PlayerScreen from './src/screens/PlayerScreen';
import {
  STORAGE_KEY_SERVER_URL,
  STORAGE_KEY_SCREEN_ID,
  STORAGE_KEY_SCREEN_TOKEN,
} from './src/utils/constants';

// ---------------------------------------------------------------------------
// ErrorBoundary — catches unhandled JS exceptions anywhere in the tree and
// shows a plain black "Reloading…" screen instead of the React Native red
// error overlay. Remounts the child tree after 3 seconds to recover.
// ---------------------------------------------------------------------------
interface EBState { hasError: boolean }
class ErrorBoundary extends Component<{ children: React.ReactNode }, EBState> {
  private reloadTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): EBState {
    return { hasError: true };
  }

  componentDidCatch() {
    this.reloadTimer = setTimeout(() => {
      this.setState({ hasError: false });
    }, 3000);
  }

  componentWillUnmount() {
    if (this.reloadTimer !== null) clearTimeout(this.reloadTimer);
  }

  render() {
    if (this.state.hasError) {
      return (
        <View style={styles.errorRoot}>
          <StatusBar hidden />
          <Text style={styles.errorText}>Reloading…</Text>
        </View>
      );
    }
    return this.props.children;
  }
}

// ---------------------------------------------------------------------------
// Root app
// ---------------------------------------------------------------------------
function AppRoot() {
  const [ready, setReady] = useState<boolean | null>(null);
  const [paired, setPaired] = useState<PairedInfo | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [url, id, token] = await Promise.all([
          AsyncStorage.getItem(STORAGE_KEY_SERVER_URL),
          AsyncStorage.getItem(STORAGE_KEY_SCREEN_ID),
          AsyncStorage.getItem(STORAGE_KEY_SCREEN_TOKEN),
        ]);
        if (url && id && token) {
          setPaired({ serverUrl: url, screenId: parseInt(id, 10), token });
        }
      } catch {}
      setReady(true);
    })();
  }, []);

  function handleReady(info: PairedInfo) {
    setPaired(info);
  }

  async function handleUnpair() {
    await Promise.all([
      AsyncStorage.removeItem(STORAGE_KEY_SERVER_URL),
      AsyncStorage.removeItem(STORAGE_KEY_SCREEN_ID),
      AsyncStorage.removeItem(STORAGE_KEY_SCREEN_TOKEN),
    ]).catch(() => {});
    setPaired(null);
  }

  if (!ready) {
    return (
      <View style={styles.loadingRoot}>
        <StatusBar hidden />
        <ActivityIndicator size="large" color="#3b82f6" />
      </View>
    );
  }

  if (!paired) {
    return (
      <View style={styles.root}>
        <StatusBar hidden />
        <ServerSetupScreen onReady={handleReady} />
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      <PlayerScreen
        screenId={paired.screenId}
        token={paired.token}
        serverUrl={paired.serverUrl}
        onUnpair={handleUnpair}
      />
    </View>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <AppRoot />
    </ErrorBoundary>
  );
}

const styles = StyleSheet.create({
  root:        { flex: 1, backgroundColor: '#0f172a' },
  loadingRoot: { flex: 1, backgroundColor: '#0f172a', justifyContent: 'center', alignItems: 'center' },
  errorRoot:   { flex: 1, backgroundColor: '#000', justifyContent: 'center', alignItems: 'center' },
  errorText:   { color: '#64748b', fontSize: 16 },
});
