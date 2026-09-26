// Global Vitest setup.
//
// Registers the jest-dom matchers (toBeInTheDocument, toHaveAttribute, ...) that the
// component tests rely on, and clears the DOM between tests so rendered trees from one
// test cannot leak into the next.
import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
});
