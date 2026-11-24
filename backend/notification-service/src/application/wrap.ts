/**
 * Greedy word wrap for free text in a plain-text email. It keeps the text's
 * own line breaks (CRLF included), collapses runs of blanks between words,
 * and leaves a word longer than the width whole on its own line rather than
 * cutting it.
 */
export function wrap(text: string, width: number): string[] {
  const lines: string[] = [];
  for (const paragraph of text.split(/\r?\n/)) {
    let line = '';
    for (const word of paragraph.split(/\s+/).filter((w) => w !== '')) {
      if (line === '') {
        line = word;
      } else if (line.length + 1 + word.length <= width) {
        line = `${line} ${word}`;
      } else {
        lines.push(line);
        line = word;
      }
    }
    lines.push(line);
  }
  return lines;
}
