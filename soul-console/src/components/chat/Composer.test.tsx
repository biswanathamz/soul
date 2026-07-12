import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useChatStore } from '../../state/chatStore';
import { Composer } from './Composer';

const initialState = useChatStore.getState();

beforeEach(() => {
  useChatStore.setState(initialState, true);
});

describe('Composer', () => {
  it('Enter sends the trimmed draft and clears the box', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    useChatStore.setState({ send });
    const user = userEvent.setup();
    render(<Composer />);

    const box = screen.getByPlaceholderText('Ask SOUL anything…');
    await user.type(box, '  hello soul  {Enter}');

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith('hello soul');
    expect(box).toHaveValue('');
  });

  it('Shift+Enter inserts a newline instead of sending', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    useChatStore.setState({ send });
    const user = userEvent.setup();
    render(<Composer />);

    const box = screen.getByPlaceholderText('Ask SOUL anything…');
    await user.type(box, 'line one{Shift>}{Enter}{/Shift}line two');

    expect(send).not.toHaveBeenCalled();
    expect(box).toHaveValue('line one\nline two');
  });

  it('Enter on an empty draft sends nothing', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    useChatStore.setState({ send });
    const user = userEvent.setup();
    render(<Composer />);

    await user.type(screen.getByPlaceholderText('Ask SOUL anything…'), '{Enter}');
    expect(send).not.toHaveBeenCalled();
  });

  it('disables the send button while a task is in flight', () => {
    useChatStore.setState({ sending: true });
    render(<Composer />);
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });
});
