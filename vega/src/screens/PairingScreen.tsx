import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  Animated,
  Image,
} from 'react-native';
import { SERVER_URL } from '../utils/constants';

interface Props {
  pairingCode: string | null;
  error: string | null;
  loading: boolean;
}

export default function PairingScreen({ pairingCode, error, loading }: Props) {
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    const pulse = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1.05, duration: 1000, useNativeDriver: true }),
        Animated.timing(pulseAnim, { toValue: 1, duration: 1000, useNativeDriver: true }),
      ])
    );
    pulse.start();
    return () => pulse.stop();
  }, [pulseAnim]);

  return (
    <View style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.brand}>digipal</Text>
        <Text style={styles.title}>Digital Signage Player</Text>

        {loading && !pairingCode ? (
          <ActivityIndicator size="large" color="#2aabb3" style={styles.loader} />
        ) : error ? (
          <View style={styles.errorBox}>
            <Text style={styles.errorText}>{error}</Text>
            <Text style={styles.errorSub}>Retrying...</Text>
          </View>
        ) : (
          <>
            <Text style={styles.instruction}>
              To pair this screen, go to your Digipal dashboard
            </Text>
            <Text style={styles.instruction}>
              and click <Text style={styles.bold}>Pair New Screen</Text>, then enter:
            </Text>

            <Animated.View style={[styles.codeBox, { transform: [{ scale: pulseAnim }] }]}>
              <Text style={styles.code}>{pairingCode}</Text>
            </Animated.View>

            <Text style={styles.url}>{SERVER_URL}</Text>
            <Text style={styles.waiting}>Waiting for pairing...</Text>
          </>
        )}
      </View>

      <View style={styles.footer}>
        <Text style={styles.footerText}>digipalsignage.com</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f1117',
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    alignItems: 'center',
    paddingHorizontal: 60,
  },
  brand: {
    fontSize: 48,
    fontWeight: '700',
    color: '#2aabb3',
    letterSpacing: 2,
    marginBottom: 8,
  },
  title: {
    fontSize: 22,
    color: '#94a3b8',
    marginBottom: 48,
    letterSpacing: 1,
  },
  instruction: {
    fontSize: 20,
    color: '#cbd5e1',
    textAlign: 'center',
    lineHeight: 30,
  },
  bold: {
    fontWeight: '700',
    color: '#f1f5f9',
  },
  codeBox: {
    marginTop: 36,
    marginBottom: 24,
    backgroundColor: '#1e293b',
    borderRadius: 16,
    borderWidth: 2,
    borderColor: '#2aabb3',
    paddingHorizontal: 48,
    paddingVertical: 24,
  },
  code: {
    fontSize: 72,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 12,
    textAlign: 'center',
    fontFamily: 'monospace',
  },
  url: {
    fontSize: 18,
    color: '#64748b',
    marginBottom: 16,
  },
  waiting: {
    fontSize: 16,
    color: '#2aabb3',
    opacity: 0.8,
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
    color: '#64748b',
    marginTop: 8,
  },
  footer: {
    position: 'absolute',
    bottom: 32,
  },
  footerText: {
    fontSize: 14,
    color: '#334155',
  },
});
