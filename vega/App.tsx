import React from 'react';
import { StatusBar, View, StyleSheet } from 'react-native';
import { useScreenPairing } from './src/hooks/useScreenPairing';
import PairingScreen from './src/screens/PairingScreen';
import PlayerScreen from './src/screens/PlayerScreen';

export default function App() {
  const { pairingCode, paired, loading, error, unpair } = useScreenPairing();

  return (
    <View style={styles.root}>
      <StatusBar hidden />
      {paired ? (
        <PlayerScreen
          screenId={paired.screenId}
          token={paired.token}
          onUnpair={unpair}
        />
      ) : (
        <PairingScreen
          pairingCode={pairingCode}
          error={error}
          loading={loading}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#0f1117',
  },
});
