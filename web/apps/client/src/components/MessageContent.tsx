/**
 * Minimal markdown-ish renderer shared by the SaaS chat and the Host mobile chat.
 * Fenced code blocks become `<pre>`, blank-line separated runs become paragraphs.
 */
export function MessageContent({ content }: { content: string }) {
  const blocks = content.split(/```/);
  return (
    <div className="message-content">
      {blocks.map((block, index) => {
        if (index % 2 === 1) {
          const lines = block.replace(/^\w+\r?\n/, "");
          return (
            <pre key={`code-${index}`}>
              <code>{lines}</code>
            </pre>
          );
        }
        return block
          .split(/\n{2,}/)
          .filter(Boolean)
          .map((paragraph, paragraphIndex) => <p key={`paragraph-${index}-${paragraphIndex}`}>{paragraph}</p>);
      })}
    </div>
  );
}
