import Stack from '@mui/material/Stack';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { PACKS } from '../../../region/packs';
import { COUNTRY_Z } from '../../../test/fixtures';
import { Money } from './Money';

const meta = {
  title: 'Design system/Money',
  component: Money,
  args: { amount: '1250000.5', currency: COUNTRY_Z.currency },
} satisfies Meta<typeof Money>;

export default meta;
type Story = StoryObj<typeof meta>;

export const CountryZ: Story = {};
export const Zero: Story = { args: { amount: '0' } };
export const Negative: Story = { args: { amount: '-42.125' } };

/** Every currency the country packs describe, at its own minor units. */
export const EveryPackCurrency: Story = {
  render: () => (
    <Stack spacing={1}>
      {Object.values(PACKS).map((pack) => (
        <Money key={pack.country} amount="1250000.456" currency={pack.currency} />
      ))}
    </Stack>
  ),
};
