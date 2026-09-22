// @ts-check
// Syntax the staff console forbids, shared by eslint.config.js and its tests.

const HEX_MESSAGE = 'Use a design token (palette.* or var(--fs-*)), not a raw hex colour (D-37).';

/** D-37: every colour comes from design tokens; a hex literal is a colour no test measured. */
export const hexColour = [
  {
    selector: 'Literal[value=/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/]',
    message: HEX_MESSAGE,
  },
  { selector: 'TemplateElement[value.raw=/#[0-9a-fA-F]{6}\\b/]', message: HEX_MESSAGE },
];

const LOGICAL_MESSAGE =
  'Use a logical property (marginInlineStart, insetInlineEnd, borderInlineStart, textAlign: "start", ...) so the layout mirrors in right-to-left (ADR 0023, ADR 0080).';

/** ADR 0080: physical left/right style properties do not mirror in right-to-left. */
export const physicalProperty = [
  {
    selector:
      'Property[key.name=/^(margin|padding|border)(Left|Right)|^(left|right)$|^(ml|mr|pl|pr)$|^scroll(Margin|Padding)(Left|Right)$/]',
    message: LOGICAL_MESSAGE,
  },
  {
    selector: 'Property[key.value=/^(margin|padding|border)-(left|right)|^(left|right)$/]',
    message: LOGICAL_MESSAGE,
  },
  {
    selector: 'Property[key.name=/^(textAlign|float|clear)$/][value.value=/^(left|right)$/]',
    message: LOGICAL_MESSAGE,
  },
];
