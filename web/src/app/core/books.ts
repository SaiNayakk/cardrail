export interface AccountEntry {
  name: string;
  balance: number;
}

export interface AccountGroup {
  label: string;
  count: number;
  total: number;
  accounts: AccountEntry[];
}

export interface BookAccountSections {
  bankAndDue: AccountEntry[];
  incomeAndExpense: AccountEntry[];
  cardholderGroup: AccountGroup;
  merchantGroup: AccountGroup;
  other: AccountEntry[];
}

export interface PartyBookMeta {
  id: string;
  name: string;
  role: string;
}

export const BOOK_PARTIES: PartyBookMeta[] = [
  { id: 'kestrel', name: 'Kestrel Bank', role: 'Cardholder bank (issuer)' },
  { id: 'railnet', name: 'Railnet', role: 'Card payment network' },
  { id: 'railpay-acquiring', name: 'Railpay Acquiring', role: 'Merchant acquirer' },
];

/**
 * Groups and sorts double-entry accounts for an ecosystem party book:
 * 1. Bank and due accounts first (alphabetical by name)
 * 2. Income and expense accounts next (alphabetical by name)
 * 3. Cardholder and merchant accounts collapsed under their respective group total
 * 4. Any other accounts (alphabetical by name)
 */
export function groupBookAccounts(
  accounts: Record<string, number> | null | undefined,
): BookAccountSections {
  const bankAndDue: AccountEntry[] = [];
  const incomeAndExpense: AccountEntry[] = [];
  const cardholderAccounts: AccountEntry[] = [];
  const merchantAccounts: AccountEntry[] = [];
  const other: AccountEntry[] = [];

  if (accounts && typeof accounts === 'object') {
    for (const [name, balance] of Object.entries(accounts)) {
      const entry: AccountEntry = {
        name,
        balance: typeof balance === 'number' && !Number.isNaN(balance) ? balance : 0,
      };

      if (name.startsWith('bank:') || name.startsWith('due:')) {
        bankAndDue.push(entry);
      } else if (name.startsWith('income:') || name.startsWith('expense:')) {
        incomeAndExpense.push(entry);
      } else if (name.startsWith('cardholder:')) {
        cardholderAccounts.push(entry);
      } else if (name.startsWith('merchant:')) {
        merchantAccounts.push(entry);
      } else {
        other.push(entry);
      }
    }
  }

  const sortByName = (a: AccountEntry, b: AccountEntry) => a.name.localeCompare(b.name);
  bankAndDue.sort(sortByName);
  incomeAndExpense.sort(sortByName);
  cardholderAccounts.sort(sortByName);
  merchantAccounts.sort(sortByName);
  other.sort(sortByName);

  const cardholderTotal = cardholderAccounts.reduce((sum, a) => sum + a.balance, 0);
  const merchantTotal = merchantAccounts.reduce((sum, a) => sum + a.balance, 0);

  return {
    bankAndDue,
    incomeAndExpense,
    cardholderGroup: {
      label: `${cardholderAccounts.length} cardholder ${cardholderAccounts.length === 1 ? 'account' : 'accounts'}`,
      count: cardholderAccounts.length,
      total: cardholderTotal,
      accounts: cardholderAccounts,
    },
    merchantGroup: {
      label: `${merchantAccounts.length} merchant ${merchantAccounts.length === 1 ? 'account' : 'accounts'}`,
      count: merchantAccounts.length,
      total: merchantTotal,
      accounts: merchantAccounts,
    },
    other,
  };
}
