import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Image,
  ScrollView,
  ActivityIndicator,
} from 'react-native';
import { SERVER_URL } from '../utils/constants';

const logoAsset = require('../assets/digipal_logo.png');

interface Props {
  onSetup: (serverUrl: string) => Promise<void>;
}

export default function ServerSetupScreen({ onSetup }: Props) {
  const [manualUrl, setManualUrl] = useState('');
  const [manualError, setManualError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleCloud() {
    setLoading(true);
    try {
      await onSetup(SERVER_URL);
    } catch {
      setManualError('Could not connect to the cloud server. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  async function handleManual() {
    let url = manualUrl.trim().replace(/\/+$/, '');
    if (!url) {
      setManualError('Please enter a server URL.');
      return;
    }
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = 'http://' + url;
    }
    setManualError('');
    setLoading(true);
    try {
      await onSetup(url);
    } catch {
      setManualError('Could not connect to that server. Please check the address.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
      <View style={styles.container}>
        <Image source={logoAsset} style={styles.logo} resizeMode="contain" />
        <Text style={styles.subtitle}>Connect to your signage server</Text>

        <View style={[styles.card, styles.cloudCard]}>
          <Text style={[styles.cardTitle, styles.cloudTitle]}>Digipal Cloud</Text>
          <Text style={styles.cardDesc}>
            Connect to the hosted Digipal service. No local server needed.
          </Text>
          <TouchableOpacity
            style={[styles.btn, styles.btnCloud]}
            onPress={handleCloud}
            disabled={loading}
            activeOpacity={0.85}
          >
            {loading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.btnText}>Use Cloud Server</Text>
            )}
          </TouchableOpacity>
        </View>

        <View style={styles.orRow}>
          <View style={styles.orLine} />
          <Text style={styles.orText}>OR</Text>
          <View style={styles.orLine} />
        </View>

        <View style={[styles.card, styles.manualCard]}>
          <Text style={[styles.cardTitle, styles.manualTitle]}>Manual Server Address</Text>
          <Text style={styles.cardDesc}>
            Enter your server URL directly. Use http:// for local network servers.
          </Text>
          <TextInput
            style={styles.input}
            value={manualUrl}
            onChangeText={t => {
              setManualUrl(t);
              setManualError('');
            }}
            placeholder="e.g. http://192.168.1.100:8787"
            placeholderTextColor="#94a3b8"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
          />
          {!!manualError && <Text style={styles.errorText}>{manualError}</Text>}
          <TouchableOpacity
            style={[styles.btn, styles.btnManual]}
            onPress={handleManual}
            disabled={loading}
            activeOpacity={0.85}
          >
            <Text style={styles.btnText}>Connect</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.footerText}>
          Your connection details are private and secure.
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: {
    flexGrow: 1,
  },
  container: {
    flex: 1,
    backgroundColor: '#f8fafc',
    alignItems: 'center',
    paddingVertical: 48,
    paddingHorizontal: 24,
  },
  logo: {
    width: 280,
    height: 72,
    marginBottom: 12,
  },
  subtitle: {
    fontSize: 15,
    color: '#64748b',
    marginBottom: 36,
  },
  card: {
    width: '100%',
    maxWidth: 480,
    borderRadius: 16,
    padding: 20,
    borderWidth: 1,
  },
  cloudCard: {
    backgroundColor: '#eff6ff',
    borderColor: '#bfdbfe',
  },
  manualCard: {
    backgroundColor: '#f0fdf4',
    borderColor: '#bbf7d0',
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: '700',
    marginBottom: 6,
  },
  cloudTitle: {
    color: '#1e40af',
  },
  manualTitle: {
    color: '#065f46',
  },
  cardDesc: {
    fontSize: 13,
    color: '#64748b',
    lineHeight: 20,
    marginBottom: 16,
  },
  btn: {
    borderRadius: 10,
    paddingVertical: 13,
    alignItems: 'center',
  },
  btnCloud: {
    backgroundColor: '#3b82f6',
  },
  btnManual: {
    backgroundColor: '#10b981',
  },
  btnText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
  },
  orRow: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    maxWidth: 480,
    marginVertical: 16,
  },
  orLine: {
    flex: 1,
    height: 1,
    backgroundColor: '#e2e8f0',
  },
  orText: {
    marginHorizontal: 12,
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: '700',
  },
  input: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#cbd5e1',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 11,
    fontSize: 14,
    color: '#0f172a',
    marginBottom: 12,
  },
  errorText: {
    color: '#ef4444',
    fontSize: 12,
    marginBottom: 8,
  },
  footerText: {
    color: '#94a3b8',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 28,
  },
});
