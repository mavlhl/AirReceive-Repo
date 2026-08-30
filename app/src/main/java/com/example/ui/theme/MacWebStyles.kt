package com.example.ui.theme

/** Shared macOS-style CSS variables for embedded web portals (gateway + local upload). */
object MacWebStyles {
    const val ROOT_VARS =
        """
        :root, [data-theme="dark"] {
          color-scheme: dark light;
          --mac-window: #000000;
          --mac-content: #1c1c1e;
          --mac-secondary: #2c2c2e;
          --mac-tertiary: #3a3a3c;
          --mac-label: #ffffff;
          --mac-label-secondary: rgba(235, 235, 245, 0.6);
          --mac-blue: #007aff;
          --mac-green: #30d158;
          --mac-red: #ff453a;
          --mac-orange: #ff9f0a;
          --mac-glass: rgba(44, 44, 46, 0.88);
          --mac-input-bg: #2c2c2e;
          --border-color: rgba(255, 255, 255, 0.1);
          --toast-error-bg: rgba(255, 69, 58, 0.15);
          --toast-error-text: #ff9f9a;
          --toast-success-bg: rgba(48, 209, 88, 0.15);
          --toast-success-text: #7ddea0;
          --mac-radius: 12px;
          --mac-radius-lg: 16px;
        }
        [data-theme="light"] {
          --mac-window: #ffffff;
          --mac-content: #f2f2f7;
          --mac-secondary: #ffffff;
          --mac-tertiary: #e5e5ea;
          --mac-label: #000000;
          --mac-label-secondary: rgba(60, 60, 67, 0.65);
          --mac-glass: rgba(255, 255, 255, 0.94);
          --mac-input-bg: #ffffff;
          --border-color: rgba(0, 0, 0, 0.1);
          --toast-error-bg: rgba(255, 69, 58, 0.12);
          --toast-error-text: #c62828;
          --toast-success-bg: rgba(48, 209, 88, 0.14);
          --toast-success-text: #1b7d3e;
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
        """

    const val THEME_SCRIPT =
        """
        (function(){try{var k='airreceive-theme';var t=localStorage.getItem(k);var d=t?t==='dark':window.matchMedia('(prefers-color-scheme: dark)').matches;document.documentElement.setAttribute('data-theme',d?'dark':'light');var btn=document.querySelector('.theme-toggle-portal');if(btn)btn.textContent=d?'☀️':'🌙';window.__toggleAirReceiveTheme=function(){var n=document.documentElement.getAttribute('data-theme')==='dark'?'light':'dark';document.documentElement.setAttribute('data-theme',n);localStorage.setItem(k,n);if(btn)btn.textContent=n==='dark'?'☀️':'🌙';};}catch(e){}})();
        """

    /** Matches ic_launcher_foreground — blue circle, signal arcs, receive arrow. */
    const val LOGO_SVG =
        """
        <svg class="airreceive-logo" viewBox="0 0 108 108" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
          <path fill="#007AFF" d="M54,20 A34,34 0 1,1 53.9,20 Z"/>
          <path fill="none" stroke="#FFFFFF" stroke-width="3" stroke-linecap="round" stroke-opacity="0.35" d="M34,42 A24,24 0 0,1 74,42"/>
          <path fill="none" stroke="#FFFFFF" stroke-width="3.5" stroke-linecap="round" d="M40,48 A18,18 0 0,1 68,48"/>
          <path fill="#FFFFFF" d="M54,52 L54,72 L68,72 L68,68 L58,68 L58,52 Z"/>
          <path fill="#FFFFFF" fill-opacity="0.9" d="M46,56 L46,64 L50,64 L50,56 Z"/>
        </svg>
        """
}
