package com.example.ui.theme

/** Shared macOS-style CSS variables for embedded web portals (gateway + local upload). */
object MacWebStyles {
    const val ROOT_VARS =
        """
        :root {
          color-scheme: dark light;
          --mac-window: #000000;
          --mac-content: #1c1c1e;
          --mac-secondary: #2c2c2e;
          --mac-tertiary: #3a3a3c;
          --mac-separator: rgba(84, 84, 88, 0.65);
          --mac-label: #ffffff;
          --mac-label-secondary: rgba(235, 235, 245, 0.6);
          --mac-blue: #007aff;
          --mac-green: #30d158;
          --mac-red: #ff453a;
          --mac-orange: #ff9f0a;
          --mac-glass: rgba(44, 44, 46, 0.72);
          --mac-radius: 12px;
          --mac-radius-lg: 16px;
        }
        """

    const val BODY_BASE =
        """
        body {
          font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
          background-color: var(--mac-window);
          color: var(--mac-label);
          margin: 0;
          padding: 0;
        }
        [data-theme="light"] {
          --mac-window: #ffffff;
          --mac-content: #f2f2f7;
          --mac-secondary: #ffffff;
          --mac-tertiary: #e5e5ea;
          --mac-label: #000000;
          --mac-label-secondary: rgba(60, 60, 67, 0.6);
          --mac-glass: rgba(255, 255, 255, 0.82);
        }
        """

    const val THEME_SCRIPT =
        """
        (function(){try{var k='airreceive-theme';var t=localStorage.getItem(k);var d=t?t==='dark':true;document.documentElement.setAttribute('data-theme',d?'dark':'light');window.__toggleAirReceiveTheme=function(){var n=document.documentElement.getAttribute('data-theme')==='dark'?'light':'dark';document.documentElement.setAttribute('data-theme',n);localStorage.setItem(k,n);};}catch(e){}})();
        """
}
