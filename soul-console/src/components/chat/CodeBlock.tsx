import { useEffect, useState } from 'react';

export function CodeBlock({ code, lang }: { code: string; lang?: string }) {
  const [html, setHtml] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let active = true;
    // shiki is lazy-loaded so it never lands in the initial bundle (TDD §2).
    import('shiki')
      .then(({ codeToHtml }) => codeToHtml(code, { lang: lang ?? 'text', theme: 'github-dark-default' }))
      .then((rendered) => {
        if (active) setHtml(rendered);
      })
      .catch(() => {
        /* unknown language or load failure — plain <pre> fallback stays */
      });
    return () => {
      active = false;
    };
  }, [code, lang]);

  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <div className="group relative my-2">
      <div className="flex items-center justify-between rounded-t-lg border border-b-0 border-line bg-surface2 px-3 py-1">
        <span className="font-mono text-[10px] uppercase tracking-widest text-muted">
          {lang ?? 'code'}
        </span>
        <button
          onClick={copy}
          className="rounded px-2 py-0.5 font-mono text-[10px] uppercase tracking-wider text-muted hover:text-accent"
        >
          {copied ? '✓ copied' : 'copy'}
        </button>
      </div>
      {html ? (
        <div
          className="overflow-hidden rounded-b-lg border border-t-0 border-line [&>pre]:my-0 [&>pre]:rounded-none"
          dangerouslySetInnerHTML={{ __html: html }}
        />
      ) : (
        <pre className="fallback my-0 rounded-b-lg rounded-t-none border border-t-0 border-line">
          <code>{code}</code>
        </pre>
      )}
    </div>
  );
}
