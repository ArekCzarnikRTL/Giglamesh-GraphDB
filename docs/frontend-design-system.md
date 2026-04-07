# Frontend Design System

Generische Design-Spezifikation zur Wiederverwendung in anderen Frontends.

---

## Tech Stack

| Kategorie | Technologie | Version |
|-----------|------------|---------|
| Framework | Next.js | 16.x |
| UI Library | React | 19.x |
| Sprache | TypeScript | 5.x |
| Styling | Tailwind CSS | v4 (PostCSS) |
| Primitives | @base-ui/react | 1.x |
| Varianten | Class Variance Authority (CVA) | 0.7.x |
| Icons | Lucide React | 0.5x |
| State | Zustand | 5.x |
| Fonts | Inter, JetBrains Mono | Google Fonts |

### Utilities

```ts
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

---

## Farbsystem

Alle Farben sind als CSS Custom Properties im **OKLCh-Farbraum** definiert (perceptually uniform).

### Theme-Variablen

```css
:root {
  /* Primär */
  --primary: oklch(0.62 0.19 260);
  --primary-foreground: oklch(0.98 0 0);

  /* Hintergrund */
  --background: oklch(0.13 0.02 260);
  --foreground: oklch(0.93 0.01 250);

  /* Karten */
  --card: oklch(0.17 0.02 260);
  --card-foreground: oklch(0.93 0.01 250);

  /* Gedämpft */
  --muted: oklch(0.22 0.03 260);
  --muted-foreground: oklch(0.55 0.03 250);

  /* Sekundär */
  --secondary: oklch(0.22 0.03 260);
  --secondary-foreground: oklch(0.93 0.01 250);

  /* Akzent */
  --accent: oklch(0.22 0.03 260);
  --accent-foreground: oklch(0.93 0.01 250);

  /* Destruktiv */
  --destructive: oklch(0.65 0.2 25);

  /* Rahmen & Eingaben */
  --border: oklch(1 0 0 / 10%);
  --input: oklch(1 0 0 / 12%);
  --ring: oklch(0.62 0.19 260);

  /* Popover */
  --popover: oklch(0.17 0.02 260);
  --popover-foreground: oklch(0.93 0.01 250);

  /* Sidebar */
  --sidebar: oklch(0.15 0.025 260);
  --sidebar-foreground: oklch(0.93 0.01 250);
  --sidebar-primary: oklch(0.62 0.19 260);
  --sidebar-accent: oklch(0.22 0.03 260);
  --sidebar-border: oklch(1 0 0 / 10%);

  /* Chart-Farben */
  --chart-1: oklch(0.62 0.19 260);  /* Blau */
  --chart-2: oklch(0.65 0.15 160);  /* Teal */
  --chart-3: oklch(0.6 0.18 300);   /* Violett */
  --chart-4: oklch(0.7 0.15 60);    /* Gelb-Grün */
  --chart-5: oklch(0.6 0.2 30);     /* Orange-Rot */

  /* Border Radius */
  --radius: 0.625rem;
}
```

### Dark Mode

Dark Mode ist der einzige Modus (kein Light Mode). Aktivierung über CSS-Klasse auf `<html>`:

```css
@custom-variant dark (&:is(.dark *));
```

```html
<html class="dark">
```

Dark-spezifische Anpassungen:

```css
dark:bg-input/30        /* Eingabefelder */
dark:hover:bg-input/50  /* Hover */
dark:border-input       /* Dunklere Rahmen */
```

---

## Typografie

### Schriftarten

| Rolle | Schrift | CSS Variable |
|-------|---------|-------------|
| Body / UI | Inter | `--font-sans` |
| Code / Monospace | JetBrains Mono | `--font-geist-mono` |
| Überschriften | Inter | `--font-heading` |

### Schriftgrößen

| Klasse | Größe | Verwendung |
|--------|-------|------------|
| `text-xs` | 0.75rem (12px) | Kleine Labels, Badges |
| `text-sm` | 0.875rem (14px) | Standard Body-Text |
| `text-base` | 1rem (16px) | Überschriften, wichtiger Text |
| `text-lg` | 1.125rem (18px) | Große Überschriften |

### Schriftstärken

| Klasse | Gewicht | Verwendung |
|--------|---------|------------|
| `font-normal` | 400 | Body-Text |
| `font-medium` | 500 | Labels, Überschriften |
| `font-semibold` | 600 | Betonte Überschriften |
| `font-bold` | 700 | Starke Betonung |

### Zeilenhöhen

- `leading-snug` (1.375) – Überschriften
- Standard (1.5) – Body-Text
- `leading-relaxed` (1.625) – Lesetext

---

## Layout-System

### Grundstruktur

Das Layout ist ein flexbox-basiertes 3-Spalten-Design mit optionalen Bottom-Panels:

```
┌──────────────────────────────────────────────────┐
│ Toolbar (fixed top)                              │
├──────┬───────────┬──────────────┬────────────────┤
│ Icon │ Sidebar   │ Hauptbereich │ Detail-Panel   │
│ Bar  │ (w-60)    │ (flex-1)     │ (w-80)         │
│(w-10)│           │              │                │
├──────┴───────────┴──────────────┴────────────────┤
│ Bottom-Panel (max-h-[30vh])      │ Status (w-64) │
└──────────────────────────────────┴───────────────┘
```

### Feste Breiten

| Element | Breite | Tailwind |
|---------|--------|----------|
| Icon-Leiste | 40px | `w-10` |
| Sidebar | 240px | `w-60` |
| Detail-Panel | 320px | `w-80` |
| Status-Panel | 256px | `w-64` |
| Hauptbereich | Flexibel | `flex-1` |

### Flex-Struktur

```tsx
{/* Root */}
<div className="flex h-screen">
  {/* Icon-Leiste */}
  <div className="w-10 flex flex-col border-r" />

  {/* Sidebar (einklappbar) */}
  <div className="w-60 flex flex-col border-r" />

  {/* Hauptbereich */}
  <div className="flex flex-col flex-1 overflow-hidden">
    {/* Toolbar */}
    <div className="p-2 border-b" />

    {/* Content + Detail */}
    <div className="flex flex-1 overflow-hidden">
      <div className="flex-1" />
      <div className="w-80 border-l" />
    </div>

    {/* Bottom-Panels */}
    <div className="flex border-t overflow-hidden max-h-[30vh]">
      <div className="flex-1 min-w-0" />
      <div className="w-64 border-l" />
    </div>
  </div>
</div>
```

---

## Spacing & Sizing

### Abstands-Skala

| Klasse | Wert | Verwendung |
|--------|------|------------|
| `gap-1` | 4px | Minimaler Abstand |
| `gap-1.5` | 6px | Kompakter Abstand |
| `gap-2` | 8px | Standard-Abstand |
| `gap-3` | 12px | Mittlerer Abstand |
| `gap-4` | 16px | Großer Abstand |
| `gap-6` | 24px | Sektions-Abstand |

### Höhen-Standards

| Element | Höhe | Tailwind |
|---------|------|----------|
| Button xs | 24px | `h-6` |
| Button sm | 28px | `h-7` |
| Button default | 32px | `h-8` |
| Button lg | 36px | `h-9` |
| Input | 32px | `h-8` |
| Badge | 20px | `h-5` |

### Border Radius

```css
--radius: 0.625rem; /* 10px Basis */

rounded-sm   = calc(var(--radius) - 4px)    /* 6px */
rounded-md   = calc(var(--radius) - 2px)    /* 8px */
rounded-lg   = var(--radius)                /* 10px */
rounded-xl   = calc(var(--radius) * 1.4)    /* 14px */
rounded-full = 9999px                       /* Badges, Pills */
```

---

## Komponenten

### Button

Varianten über CVA:

```tsx
const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded-md text-sm font-medium transition-all",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground shadow-xs hover:bg-primary/90",
        outline: "border bg-background shadow-xs hover:bg-accent hover:text-accent-foreground",
        secondary: "bg-secondary text-secondary-foreground shadow-xs hover:bg-secondary/80",
        ghost: "hover:bg-accent hover:text-accent-foreground",
        destructive: "bg-destructive text-white shadow-xs hover:bg-destructive/90",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        xs: "h-6 rounded-md px-2 text-xs",
        sm: "h-7 rounded-md px-3",
        default: "h-8 px-3 py-1.5",
        lg: "h-9 rounded-md px-4",
        icon: "size-8",
        "icon-xs": "size-6",
        "icon-sm": "size-7",
        "icon-lg": "size-9",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  }
);
```

Micro-Interaktion: `active:translate-y-px` (1px nach unten beim Klicken).

### Card

```tsx
<Card>           {/* bg-card border rounded-xl */}
  <CardHeader>   {/* grid gap-1.5 p-4 */}
    <CardTitle />
    <CardDescription />
    <CardAction />
  </CardHeader>
  <CardContent>  {/* px-4 pb-4 */}
    ...
  </CardContent>
  <CardFooter />
</Card>
```

Größen: `default` (p-4) | `sm` (p-3)

### Dialog / Modal

```tsx
<Dialog>
  <DialogTrigger />
  <DialogOverlay />   {/* z-50, bg-black/10, backdrop-blur */}
  <DialogContent>     {/* z-50, fixed center, max-w-sm */}
    <DialogHeader>
      <DialogTitle />
      <DialogDescription />
    </DialogHeader>
    ...
    <DialogFooter />  {/* flex-col-reverse sm:flex-row */}
  </DialogContent>
</Dialog>
```

Animation: `fade-in-0 + zoom-in-95` (open), `fade-out-0 + zoom-out-95` (close).

### Tabs

Zwei Varianten:

| Variante | Stil |
|----------|------|
| `default` | Hintergrund-Wechsel (bg-muted) |
| `line` | Unterstrich-Indikator |

```tsx
<Tabs defaultValue="tab1">
  <TabsList variant="line">
    <TabsTrigger value="tab1">Tab 1</TabsTrigger>
    <TabsTrigger value="tab2">Tab 2</TabsTrigger>
  </TabsList>
  <TabsContent value="tab1">...</TabsContent>
</Tabs>
```

### Select / Dropdown

```tsx
<Select>
  <SelectTrigger>     {/* h-8, border, rounded-md */}
    <SelectValue placeholder="..." />
  </SelectTrigger>
  <SelectContent>     {/* z-50, rounded-md, border, shadow-md */}
    <SelectGroup>
      <SelectLabel />
      <SelectItem value="..." />
    </SelectGroup>
  </SelectContent>
</Select>
```

Animation: `slide-in-from-top-2 + fade-in-0 + zoom-in-95`.

### Input

```tsx
<Input className="h-8 border bg-transparent rounded-md px-3 text-sm
                  focus-visible:ring-[3px] focus-visible:ring-ring/50
                  dark:bg-input/30" />
```

### Badge

```tsx
<Badge variant="default | secondary | destructive | outline | ghost">
  Label
</Badge>
```

Höhe: `h-5`, abgerundet: `rounded-4xl`.

### Sheet (Slide-Panel)

```tsx
<Sheet>
  <SheetTrigger />
  <SheetOverlay />   {/* z-50, bg-black/10, backdrop-blur */}
  <SheetContent side="left | right | top | bottom">
    <SheetHeader>
      <SheetTitle />
      <SheetDescription />
    </SheetHeader>
    ...
  </SheetContent>
</Sheet>
```

Animation: 200ms ease-in-out Slide (2.5rem Translation).

### Alert

```tsx
<Alert variant="default | destructive">
  <AlertTitle />
  <AlertDescription />
  <AlertAction />
</Alert>
```

### ScrollArea

Custom Scrollbar-Styling mit Hover-Transition.

### Separator

1px Divider, horizontal oder vertikal.

---

## Icons

**Bibliothek:** Lucide React

### Größen

| Klasse | Größe | Verwendung |
|--------|-------|------------|
| `size-3` | 12px | In Badges |
| `size-3.5` | 14px | Kleine UI-Elemente |
| `size-4` | 16px | Standard |
| `size-5` | 20px | Betonte Icons |

### Farbgebung

Icons erben die Textfarbe (`text-current`) oder werden explizit eingefärbt:

```tsx
<Icon className="size-4 text-muted-foreground" />   // Gedämpft
<Icon className="size-4 text-primary" />             // Primär
<Icon className="size-4 text-destructive" />         // Fehler
<Icon className="size-4 text-blue-400" />            // Kategorie-Farbe
```

---

## Animationen & Transitions

### Dauer

| Dauer | Verwendung |
|-------|------------|
| 100ms | Schnelle Hover-Effekte, Dropdowns |
| 150ms | Standard-Übergänge |
| 200ms | Slide-Panels, Sheets |

### Transition-Typen

```css
transition-all          /* Allgemein */
transition-colors       /* Farbwechsel (Buttons, Links) */
transition-opacity      /* Ein-/Ausblenden */
transition-[color,box-shadow]  /* Fokus-Ring */
```

### Animations-Patterns

**Ein-/Ausblenden (Dialoge, Dropdowns):**
```
data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95
data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95
```

**Slide (Sheets):**
```
transition duration-200 ease-in-out
data-ending-style:opacity-0
data-[side=right]:data-ending-style:translate-x-[2.5rem]
```

**Micro-Interaktionen:**
- Button-Press: `active:translate-y-px`
- Loading-Spinner: `animate-spin`
- Pulse: `animate-pulse`

---

## Z-Index Layering

| Ebene | Z-Index | Elemente |
|-------|---------|----------|
| Content | 0 | Normaler Seiteninhalt |
| Sticky | auto | Fixierte Header/Footer |
| Scroll-Controls | 10 | Scroll-Buttons in Select |
| Overlays & Popups | 50 | Dialoge, Sheets, Dropdowns, Popovers |

---

## Data Attributes

### Semantische Slots

Jede Komponente hat ein `data-slot` Attribut:

```html
<button data-slot="button" />
<div data-slot="card" />
<div data-slot="dialog" />
<div data-slot="tabs" />
```

### Zustands-Attribute

```html
data-size="sm | default"
data-variant="outline | default | ghost | ..."
data-side="left | right | top | bottom"
data-orientation="horizontal | vertical"
data-active
data-disabled
data-placeholder
data-open
data-closed
```

### ARIA

```html
aria-invalid      /* Validierungs-Fehler */
aria-expanded     /* Dropdown offen/geschlossen */
aria-disabled     /* Deaktiviert */
role="alert"      /* Benachrichtigungen */
```

---

## Responsive Verhalten

### Breakpoints

| Prefix | Breite |
|--------|--------|
| `sm:` | 640px |
| `md:` | 768px |
| `lg:` | 1024px |
| `xl:` | 1280px |

### Patterns

```css
/* Dialog-Breite */
max-w-[calc(100%-2rem)] sm:max-w-sm

/* Footer-Layout */
flex-col-reverse sm:flex-row

/* Sheet-Breite */
data-[side=left]:sm:max-w-sm

/* Text-Größe */
text-base md:text-sm
```

Das Layout ist primär für Desktop optimiert (feste Sidebar-Breiten).

---

## CVA Component Pattern

Standardmuster für alle Komponenten:

```tsx
import { cva, type VariantProps } from "class-variance-authority";
import { Primitive } from "@base-ui/react";

const componentVariants = cva(
  "basis-klassen hier",
  {
    variants: {
      variant: {
        default: "...",
        secondary: "...",
      },
      size: {
        sm: "...",
        default: "...",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
);

interface ComponentProps
  extends React.ComponentProps<typeof Primitive>,
    VariantProps<typeof componentVariants> {}

function Component({ className, variant, size, ...props }: ComponentProps) {
  return (
    <Primitive
      data-slot="component"
      className={cn(componentVariants({ variant, size, className }))}
      {...props}
    />
  );
}
```

---

## Design-Zusammenfassung

| Token | Wert |
|-------|------|
| Primärfarbe | `oklch(0.62 0.19 260)` (Blau) |
| Hintergrund | `oklch(0.13 0.02 260)` (Dunkel) |
| Rahmen | `oklch(1 0 0 / 10%)` (Semi-transparent) |
| Radius | `0.625rem` (10px) |
| Body-Schrift | Inter |
| Code-Schrift | JetBrains Mono |
| Body-Größe | `text-sm` (14px) |
| Transition | 100-200ms |
| Overlay Z-Index | 50 |
| Theme | Dark-only |
| Farbraum | OKLCh |
