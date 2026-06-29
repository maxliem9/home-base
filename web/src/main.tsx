import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './i18n' // initialise i18next before anything renders
import './index.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)

// Register the service worker (#429 Phase 2b) so the app is installable and can receive Web Push.
// Best-effort and non-blocking: a registration failure (older browser, insecure context) must not
// affect the app. The actual push subscription is opt-in, driven from Settings → Notifications.
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      /* ignore — the push-enable flow re-registers and surfaces any real error */
    })
  })
}
