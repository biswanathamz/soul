import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// RTL auto-cleanup needs vitest globals; we don't use them, so register it manually.
afterEach(() => cleanup());
