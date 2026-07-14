import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/600.css';
import './theme/tokens.css';
import './index.css';
import App from './App';
import { bootSocket } from './api/socket';
import { useVoiceStore } from './state/voiceStore';
import { initWakeWord } from './voice/wakeword';

useVoiceStore.getState().init();
bootSocket();
initWakeWord();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
