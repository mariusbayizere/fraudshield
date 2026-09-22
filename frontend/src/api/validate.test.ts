import { checked } from './validate';

describe('checked (H.4)', () => {
  it('passes a response that matches the contract through unchanged', async () => {
    const body = { amount: '12.5' };
    await expect(checked('zDecimalAmount', body.amount)).resolves.toBe('12.5');
  });

  it('rejects a response that breaks the contract, in tests', async () => {
    await expect(checked('zDecimalAmount', '1e3')).rejects.toThrow();
    await expect(checked('zDecimalAmount', 12.5)).rejects.toThrow();
  });
});
