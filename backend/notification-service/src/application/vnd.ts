/**
 * "150,000,000.00 VND": the contract's `money` (digits, a dot, two decimals)
 * grouped as text, never through a float.
 */
export function vnd(amount: string): string {
  const [whole, cents] = amount.split('.');
  return `${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${cents} VND`;
}
