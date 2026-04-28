import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  Animated,
  Image,
} from 'react-native';

const logoAsset = require('../assets/digipal_logo.png');

interface Props {
  pairingCode: string | null;
  error: string | null;
  loading: boolean;
  serverUrl: string;
}

export default function PairingScreen({ pairingCode, error, loading, serverUrl }: Props) {
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    const pulse = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1.04, duration: 1200, useNativeDriver: true }),
        Animated.timing(pulseAnim, { toValue: 1, duration: 1200, useNativeDriver: true }),
      ])
    );
    pulse.start();
    return () => pulse.stop();
  }, [pulseAnim]);

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <Image source={logoAsset} style={styles.logo} resizeMode="contain" />

        {loading && !pairingCode ? (
          <ActivityIndicator size="large" color="#3b82f6" style={styles.loader} />
        ) : error ? (
          <View style={styles.errorBox}>
            <Text style={styles.errorText}>{error}</Text>
            <Text style={styles.errorSub}>Retrying...</Text>
          </View>
        ) : (
          <>
            <Text style={styles.instruction}>
              Go to your Digipal dashboard and click{' '}
              <Text style={styles.bold}>Pair New Screen</Text>, then enter:
            </Text>

            <Animated.View style={[styles.codeBox, { transform: [{ scale: pulseAnim }] }]}>
              <Text style={styles.code}>{pairingCode}</Text>
            </Animated.View>

            <Text style={styles.url}>{serverUrl}</Text>
            <Text style={styles.waiting}>Waiting for pairing...</Text>
          </>
        )}
      </View>

      <View style={styles.footer}>
        <Text style={styles.footerText}>Your connection details are private and secure.</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f8fafc',
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    alignItems: 'center',
    paddingHorizontal: 60,
  },
  logo: {
    width: 280,
    height: 72,
    marginBottom: 36,
  },
  instruction: {
    fontSize: 18,
    color: '#475569',
    textAlign: 'center',
    lineHeight: 28,
    marginBottom: 4,
  },
  bold: {
    fontWeight: '700',
    color: '#1e293b',
  },
  codeBox: {
    marginTop: 28,
    marginBottom: 20,
    backgroundColor: '#ffffff',
    borderRadius: 20,
    borderWidth: 2,
    borderColor: '#3b82f6',
    paddingHorizontal: 52,
    paddingVertical: 28,
    shadowColor: '#3b82f6',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.15,
    shadowRadius: 16,
    elevation: 8,
  },
  code: {
    fontSize: 72,
    fontWeight: '900',
    color: '#1e293b',
    letterSpacing: 12,
    textAlign: 'center',
    fontFamily: 'monospace',
  },
  url: {
    fontSize: 15,
    color: '#94a3b8',
    marginBottom: 12,
  },
  waiting: {
    fontSize: 15,
    color: '#3b82f6',
    opacity: 0.85,
  },
  loader: {
    marginTop: 48,
  },
  errorBox: {
    marginTop: 48,
    alignItems: 'center',
  },
  errorText: {
    fontSize: 18,
    color: '#ef4444',
    textAlign: 'center',
  },
  errorSub: {
    fontSize: 14,
    color: '#94a3b8',
    marginTop: 8,
  },
  footer: {
    position: 'absolute',
    bottom: 32,
  },
  footerText: {
    fontSize: 13,
    color: '#94a3b8',
  },
});
