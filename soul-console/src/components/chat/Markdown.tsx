import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CodeBlock } from './CodeBlock';

export function Markdown({ text }: { text: string }) {
  return (
    <div className="md text-sm">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ className, children }) {
            const content = String(children ?? '');
            const lang = /language-(\w+)/.exec(className ?? '')?.[1];
            const isBlock = !!className || content.includes('\n');
            if (!isBlock) return <code className="inline-code">{content}</code>;
            return <CodeBlock code={content.replace(/\n$/, '')} lang={lang} />;
          },
          // CodeBlock renders its own <pre>; unwrap the markdown one.
          pre({ children }) {
            return <>{children}</>;
          },
          a({ node: _node, ...props }) {
            return <a {...props} target="_blank" rel="noreferrer" />;
          },
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
