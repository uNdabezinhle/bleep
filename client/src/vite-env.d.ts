/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_RELAY_BASE?: string;
  readonly VITE_TURN_USER?: string;
  readonly VITE_TURN_PASS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
