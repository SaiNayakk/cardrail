/** Groups a card number or masked PAN string into chunks of 4 characters (e.g. "2860 0100 0000 0012"). */
export function groupCardNumber(raw: string | null | undefined): string {
  if (!raw) {
    return '';
  }
  const clean = raw.trim().replace(/\s+/g, '');
  if (!clean) {
    return '';
  }
  const chunks = clean.match(/.{1,4}/g);
  return chunks ? chunks.join(' ') : clean;
}
