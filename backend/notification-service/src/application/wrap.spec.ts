import { wrap } from './wrap';

describe('wrap', () => {
  it('fills lines up to the width, breaking between words', () => {
    expect(wrap('aaa bbb ccc ddd', 7)).toEqual(['aaa bbb', 'ccc ddd']);
    expect(wrap('aaa bbb ccc', 8)).toEqual(['aaa bbb', 'ccc']);
  });

  it('keeps the text’s own line breaks, CRLF included', () => {
    expect(wrap('first\nsecond\r\nthird', 72)).toEqual([
      'first',
      'second',
      'third',
    ]);
  });

  it('leaves a word longer than the width whole on its own line', () => {
    const word = 'x'.repeat(80);

    expect(wrap(`short ${word} end`, 72)).toEqual(['short', word, 'end']);
  });

  it('collapses runs of spaces and tabs between words', () => {
    expect(wrap('  a \t b  ', 72)).toEqual(['a b']);
  });

  it('a reason of blanks only is one empty line, not none', () => {
    expect(wrap('   ', 72)).toEqual(['']);
  });
});
