import React, { useState, useEffect } from 'react';
import { StatusBar, View, StyleSheet, ActivityIndicator } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useScreenPairing } from './src/hooks/useScreenPairing';
import ServerSetupScreen from './src/screens/ServerSetupScreen';
import PairingScreen from './src/screens/PairingScreen';
import PlayerScreen from './src/screens/PlayerScreen';
import { STORAGE_KEY_SERVER_URL } from './src/utils/constants';

export default function App() {
  const [serverUrl, setServerUrl] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    AsyncStorage.getItem(STORAGE_KEY_SERVER_URL)
      .then(url => setServerUrl(url))
      .catch(() => setServerUrl(null));
  }, []);

  const { pairingCode, paired, loading, error, unpair } = useScreenPairing(
    serverUrl ?? null
  );

  if (serverUrl === undefined) {
    return (
      <View style={styles.loadingRoot}>
        <StatusBar hidden />
        <ActivityIndicator size="large" color="#3b82f6" />
      </View>
    );
  }

  if (!serverUrl) {
    return (
      <View style={styles.root}>
        <StatusBar hidden />
        <ServerSetupScreen
          onSetup={async url => {
            await AsyncStorage.setItem(STORAGE_KEY_SERVER_URL, url);
            setServerUrl(url);
          }}
        />
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      {paired ? (
        <PlayerScreen
          screenId={paired.screenId}
          token={paired.token}
          serverUrl={serverUrl}
          onUnpair={unpair}
        />
      ) : (
        <PairingScreen
          pairingCode={pairingCode}
          error={error}
          loading={loading}
          serverUrl={serverUrl}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#f8fafc',
  },
  loadingRoot: {
    flex: 1,
    backgroundColor: '#f8fafc',
    justifyContent: 'center',
    alignItems: 'center',
  },
});
