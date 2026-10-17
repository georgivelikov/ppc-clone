import { EVENT_GROUPS } from "../lib/eventTypes";

/**
 * Chooses which events an endpoint receives: everything, or specific types. The value is the list the API
 * stores: ["*"] or exact event types.
 */
export function EventTypePicker({ value, onChange }: { value: string[]; onChange: (next: string[]) => void }) {
  const all = value.includes("*");

  const toggle = (type: string) =>
    onChange(value.includes(type) ? value.filter((entry) => entry !== type) : [...value.filter((entry) => entry !== "*"), type]);

  return (
    <fieldset className="space-y-3">
      <legend className="mb-1.5 text-sm font-medium">Events to send</legend>
      <label className="flex cursor-pointer items-center gap-2 text-sm">
        <input type="checkbox" checked={all} onChange={(event) => onChange(event.target.checked ? ["*"] : [])} />
        All events
      </label>
      {!all &&
        EVENT_GROUPS.map((group) => (
          <div key={group.family}>
            <p className="mb-1 text-xs font-medium text-muted uppercase">{group.label}</p>
            <ul className="space-y-1">
              {group.types.map((type) => (
                <li key={type}>
                  <label className="flex cursor-pointer items-center gap-2 font-mono text-xs">
                    <input type="checkbox" checked={value.includes(type)} onChange={() => toggle(type)} />
                    {type}
                  </label>
                </li>
              ))}
            </ul>
          </div>
        ))}
    </fieldset>
  );
}
