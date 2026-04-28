import React, { useRef, useCallback, useState } from 'react';
import {
  View,
  StyleSheet,
  TouchableWithoutFeedback,
  Text,
  Modal,
  Pressable,
} from 'react-native';
import WebView from 'react-native-webview';

interface Props {
  screenId: number;
  token: string;
  serverUrl: string;
  onUnpair: () => void;
}

export default function PlayerScreen({ screenId, token, serverUrl, onUnpair }: Props) {
  const webViewRef = useRef<WebView>(null);
  const [showMenu, setShowMenu] = useState(false);
  const [tapCount, setTapCount] = useState(0);
  const tapTimer = useRef<ReturnType<typeof setTimeout>>();

  const playerUrl = `${serverUrl}/tv?screenId=${screenId}&token=${encodeURIComponent(token)}`;

  const handleTap = useCallback(() => {
    const newCount = tapCount + 1;
    setTapCount(newCount);
    if (tapTimer.current) clearTimeout(tapTimer.current);
    if (newCount >= 5) {
      setShowMenu(true);
      setTapCount(0);
    } else {
      tapTimer.current = setTimeout(() => setTapCount(0), 2000);
    }
  }, [tapCount]);

  const injectedJS = `
    (function() {
      document.documentElement.style.cursor = 'none';
      window.isVegaPlayer = true;
      window.digipalScreenId = ${screenId};
      true;
    })();
  `;

  return (
    <View style={styles.container}>
      <TouchableWithoutFeedback onPress={handleTap}>
        <View style={StyleSheet.absoluteFill}>
          <WebView
            ref={webViewRef}
            source={{ uri: playerUrl }}
            style={styles.webview}
            injectedJavaScript={injectedJS}
            javaScriptEnabled
            domStorageEnabled
            allowsInlineMediaPlayback
            mediaPlaybackRequiresUserAction={false}
            allowsFullscreenVideo
            scrollEnabled={false}
            bounces={false}
            overScrollMode="never"
            onError={() => {
              setTimeout(() => webViewRef.current?.reload(), 3000);
            }}
            onHttpError={() => {
              setTimeout(() => webViewRef.current?.reload(), 5000);
            }}
          />
        </View>
      </TouchableWithoutFeedback>

      <Modal
        visible={showMenu}
        transparent
        animationType="fade"
        onRequestClose={() => setShowMenu(false)}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.menuBox}>
            <Text style={styles.menuTitle}>Digipal Player</Text>
            <Text style={styles.menuSub}>Screen ID: {screenId}</Text>

            <Pressable
              style={styles.menuButton}
              onPress={() => {
                setShowMenu(false);
                webViewRef.current?.reload();
              }}
            >
              <Text style={styles.menuButtonText}>Reload Player</Text>
            </Pressable>

            <Pressable
              style={[styles.menuButton, styles.menuButtonDanger]}
              onPress={() => {
                setShowMenu(false);
                onUnpair();
              }}
            >
              <Text style={styles.menuButtonText}>Unpair This Screen</Text>
            </Pressable>

            <Pressable
              style={[styles.menuButton, styles.menuButtonCancel]}
              onPress={() => setShowMenu(false)}
            >
              <Text style={[styles.menuButtonText, { color: '#94a3b8' }]}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  webview: {
    flex: 1,
    backgroundColor: '#000',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.85)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  menuBox: {
    backgroundColor: '#1e293b',
    borderRadius: 16,
    padding: 32,
    width: 360,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#334155',
  },
  menuTitle: {
    fontSize: 24,
    fontWeight: '700',
    color: '#3b82f6',
    marginBottom: 8,
  },
  menuSub: {
    fontSize: 14,
    color: '#64748b',
    marginBottom: 32,
  },
  menuButton: {
    backgroundColor: '#334155',
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 24,
    width: '100%',
    alignItems: 'center',
    marginBottom: 12,
  },
  menuButtonDanger: {
    backgroundColor: '#7f1d1d',
  },
  menuButtonCancel: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: '#334155',
  },
  menuButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#f1f5f9',
  },
});
