import { act, screen } from '@testing-library/react';
import { renderThemed } from '../../../test/render';
import { tokens } from '../../tokens';
import { CountdownChip, secondsLeft } from './CountdownChip';

const START = Date.parse('2026-09-22T12:00:00Z');

describe('CountdownChip', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: START });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('counts down each second and turns red below ten', () => {
    renderThemed(<CountdownChip deadlineMs={START + 12_000} />);
    const timer = screen.getByRole('timer');
    expect(timer).toHaveAccessibleName('12 seconds left to review');
    expect(timer).toHaveAttribute('data-urgent', 'false');
    expect(timer).toHaveStyle({ color: tokens.color.light.risk.medium.text });

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(timer).toHaveTextContent('10 s');
    expect(timer).toHaveAttribute('data-urgent', 'false');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(timer).toHaveTextContent('9 s');
    expect(timer).toHaveAttribute('data-urgent', 'true');
    expect(timer).toHaveStyle({ color: tokens.color.light.risk.high.text });
  });

  it('calls onFinalSeconds once, when fewer than five seconds remain', () => {
    const onFinalSeconds = vi.fn();
    renderThemed(<CountdownChip deadlineMs={START + 7_000} onFinalSeconds={onFinalSeconds} />);
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(onFinalSeconds).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(onFinalSeconds).toHaveBeenCalledTimes(1);
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(onFinalSeconds).toHaveBeenCalledTimes(1);
  });

  it('stops at zero', () => {
    renderThemed(<CountdownChip deadlineMs={START + 1_000} />);
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(screen.getByRole('timer')).toHaveTextContent('0 s');
  });

  it.each([
    [10_000, 10],
    [9_001, 10],
    [9_000, 9],
    [1, 1],
    [0, 0],
    [-500, 0],
  ])('%d ms left shows %d s', (ms, s) => {
    expect(secondsLeft(START + ms, START)).toBe(s);
  });
});
