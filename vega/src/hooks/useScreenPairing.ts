import { useState, useEffect, useCallback } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  SERVER_URL,
  PAIRING_POLL_INTERVAL,
  STORAGE_KEY_SCREEN_ID,
  STORAGE_KEY_SCREEN_TOKEN,
} from '../utils/constants';

export interface PairedScreen {
  screenId: number;
  token: string;
}

export function useScreenPairing() {
  const [pairingCode, setPairingCode] = useState<string | null>(null);
  const [paired, setPaired] = useState<PairedScreen | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadStoredPairing = useCallback(async () => {
    try {
      const screenId = await AsyncStorage.getItem(STORAGE_KEY_SCREEN_ID);
      const token = await AsyncStorage.getItem(STORAGE_KEY_SCREEN_TOKEN);
      if (screenId && token) {
        setPaired({ screenId: parseInt(screenId, 10), token });
        return true;
      }
    } catch {}
    return false;
  }, []);

  const fetchPairingCode = useCallback(async () => {
    try {
      const res = await fetch(`${SERVER_URL}/api/tv/pairing-code`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ platform: 'vega' }),
      });
      if (!res.ok) throw new Error('Failed to get pairing code');
      const data = await res.json();
      setPairingCode(data.code);
      return data.code;
    } catch (e: any) {
      setError('Cannot connect to server. Check your network.');
      return null;
    }
  }, []);

  const pollForPairing = useCallback(async (code: string) => {
    try {
      const res = await fetch(`${SERVER_URL}/api/tv/pairing-status/${code}`);
      if (!res.ok) return false;
      const data = await res.json();
      if (data.paired && data.screenId && data.token) {
        await AsyncStorage.setItem(STORAGE_KEY_SCREEN_ID, String(data.screenId));
        await AsyncStorage.setItem(STORAGE_KEY_SCREEN_TOKEN, data.token);
        setPaired({ screenId: data.screenId, token: data.token });
        return true;
      }
    } catch {}
    return false;
  }, []);

  const unpair = useCallback(async () => {
    await AsyncStorage.removeItem(STORAGE_KEY_SCREEN_ID);
    await AsyncStorage.removeItem(STORAGE_KEY_SCREEN_TOKEN);
    setPaired(null);
    setPairingCode(null);
    setError(null);
  }, []);

  useEffect(() => {
    let pollTimer: ReturnType<typeof setInterval>;

    (async () => {
      setLoading(true);
      const alreadyPaired = await loadStoredPairing();
      if (!alreadyPaired) {
        const code = await fetchPairingCode();
        if (code) {
          pollTimer = setInterval(async () => {
            const success = await pollForPairing(code);
            if (success) clearInterval(pollTimer);
          }, PAIRING_POLL_INTERVAL);
        }
      }
      setLoading(false);
    })();

    return () => {
      if (pollTimer) clearInterval(pollTimer);
    };
  }, [loadStoredPairing, fetchPairingCode, pollForPairing]);

  return { pairingCode, paired, loading, error, unpair };
}
