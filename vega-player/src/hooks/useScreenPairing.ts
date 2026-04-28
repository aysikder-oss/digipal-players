import { useState, useEffect, useCallback } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  PAIRING_POLL_INTERVAL,
  STORAGE_KEY_SCREEN_ID,
  STORAGE_KEY_SCREEN_TOKEN,
} from '../utils/constants';

export interface PairedScreen {
  screenId: number;
  token: string;
}

export function useScreenPairing(serverUrl: string | null) {
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

  const fetchPairingCode = useCallback(async (url: string) => {
    try {
      const res = await fetch(`${url}/api/tv/pairing-code`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ platform: 'vega' }),
      });
      if (!res.ok) throw new Error('Failed to get pairing code');
      const data = await res.json();
      setPairingCode(data.code);
      setError(null);
      return data.code as string;
    } catch {
      setError('Cannot connect to server. Check your network.');
      return null;
    }
  }, []);

  const pollForPairing = useCallback(async (url: string, code: string) => {
    try {
      const res = await fetch(`${url}/api/tv/pairing-status/${code}`);
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
    if (!serverUrl) {
      setLoading(false);
      return;
    }

    let pollTimer: ReturnType<typeof setInterval>;
    let cancelled = false;

    (async () => {
      setLoading(true);
      const alreadyPaired = await loadStoredPairing();
      if (!alreadyPaired && !cancelled) {
        const code = await fetchPairingCode(serverUrl);
        if (code && !cancelled) {
          pollTimer = setInterval(async () => {
            const success = await pollForPairing(serverUrl, code);
            if (success) clearInterval(pollTimer);
          }, PAIRING_POLL_INTERVAL);
        }
      }
      if (!cancelled) setLoading(false);
    })();

    return () => {
      cancelled = true;
      if (pollTimer) clearInterval(pollTimer);
    };
  }, [serverUrl, loadStoredPairing, fetchPairingCode, pollForPairing]);

  return { pairingCode, paired, loading, error, unpair };
}
