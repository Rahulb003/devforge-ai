import { indentWithTab } from '@codemirror/commands';
import { css } from '@codemirror/lang-css';
import { html } from '@codemirror/lang-html';
import { java } from '@codemirror/lang-java';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { python } from '@codemirror/lang-python';
import { EditorState, type Extension } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, keymap } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { useEffect, useRef } from 'react';

/** Syntax highlighting by extension; anything unknown is edited as plain text. */
function languageFor(path: string): Extension[] {
  const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  switch (extension) {
    case 'js':
    case 'jsx':
    case 'mjs':
    case 'cjs':
      return [javascript({ jsx: true })];
    case 'ts':
    case 'tsx':
      return [javascript({ jsx: true, typescript: true })];
    case 'java':
    case 'kt':
      return [java()];
    case 'py':
      return [python()];
    case 'json':
      return [json()];
    case 'md':
    case 'markdown':
      return [markdown()];
    case 'css':
      return [css()];
    case 'html':
    case 'htm':
      return [html()];
    default:
      return [];
  }
}

/**
 * The CSP style nonce the server wrote into this page (see index.html).
 *
 * CodeMirror injects <style> elements, which the policy blocks unless they carry this nonce. Under
 * `vite dev` the placeholder is never replaced and no CSP is sent, so it is dropped.
 */
function cspNonce(): string | null {
  const value = document.querySelector('meta[name="csp-nonce"]')?.getAttribute('content');
  return value && value !== '__CSP_NONCE__' ? value : null;
}

/**
 * A CodeMirror 6 editor for one file.
 *
 * Loaded lazily from the repository page, so the editor's weight is paid only by someone who
 * edits. The view is created once per file: recreating it on every keystroke would lose the cursor
 * and undo history, so `value` seeds it and later changes flow out through `onChange` only.
 */
export default function CodeEditor({
  path,
  value,
  onChange,
}: {
  path: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!host.current) return;
    const nonce = cspNonce();
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          ...(nonce ? [EditorView.cspNonce.of(nonce)] : []),
          basicSetup,
          keymap.of([indentWithTab]),
          oneDark,
          ...languageFor(path),
          EditorView.contentAttributes.of({ 'aria-label': `Editing ${path}` }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
    });
    return () => view.destroy();
    // Seeded once per file; see the component comment.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  return (
    <div
      ref={host}
      className="overflow-hidden rounded-xl border border-slate-700 text-sm [&_.cm-editor]:min-h-[24rem] [&_.cm-scroller]:font-mono"
    />
  );
}
