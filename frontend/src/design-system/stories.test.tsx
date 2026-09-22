import {
  composeStory,
  setProjectAnnotations,
  type Meta,
  type StoryObj,
} from '@storybook/react-vite';
import { render } from '@testing-library/react';
import preview from '../../.storybook/preview';
import { axeViolations } from '../test/axe';

setProjectAnnotations(preview);

interface StoryModule {
  default: Meta;
  [exportName: string]: unknown;
}
const modules = import.meta.glob<StoryModule>('./**/*.stories.tsx', { eager: true });

// Every named export of a story file is a story (CSF); the default export is its meta.
const stories = Object.entries(modules).flatMap(([file, { default: meta, ...exports }]) =>
  Object.entries(exports).map(
    ([name, story]) =>
      [
        `${file.replace('./components/', '')} › ${name}`,
        composeStory(story as StoryObj, meta, undefined, name),
      ] as const,
  ),
);

/**
 * Every Storybook story, in every state it documents, rendered and checked by axe (build prompt
 * H.4, FR-04-12). A new story is tested by existing, with no test to remember to write.
 */
describe('design-system stories', () => {
  it('finds the stories', () => {
    expect(stories.length).toBeGreaterThan(0);
  });

  it.each(stories)('%s has no axe violations', async (_name, Story) => {
    const { container } = render(<Story />);
    expect(await axeViolations(container)).toEqual([]);
  });
});
