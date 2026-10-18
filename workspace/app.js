'use strict';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SVG_NS = 'http://www.w3.org/2000/svg';
const MAX_UPLOAD_BYTES = 450_000;
const DEFAULT_SELECTION_TEXT = 'Select a transition or state.';

const STATUS_LABELS = {
  STATISTICAL_TENSION: 'Statistical tension detected',
  NO_GLOBAL_TENSION_DETECTED: 'No global statistical tension detected',
  NO_CYCLES_TO_TEST: 'No cycles to test',
  INFEASIBLE_LOCK_POLICY: 'Incompatible exact locks — no repair issued',
};

const EDGE_COLORS = {
  selected: '#713ab0',
  cycle: '#b85a00',
  locked: '#2457a4',
  changed: '#187744',
  default: '#9aa2ae',
};

const ILL_CONDITIONED_THRESHOLD = 1e8;

const VIEW_WIDTH = 900;
const VIEW_HEIGHT = 540;
const VIEW_ASPECT = VIEW_HEIGHT / VIEW_WIDTH;
const MIN_VIEW_WIDTH = 120;
const MAX_VIEW_WIDTH = 3600;

const LAYOUT = {
  centerX: 450,
  centerY: 270,
  initialRadiusX: 265,
  initialRadiusY: 205,
  iterations: 180,
  repulsion: 7000,
  springStiffness: 0.018,
  gravity: 0.002,
  maxStep: 8,
  minX: 65,
  maxX: 835,
  minY: 45,
  maxY: 490,
  fitPadding: 90,
  minFitWidth: 360,
};

const NODE_RADIUS = 17;
const NODE_LABEL_OFFSET = 33;
const NODE_LABEL_MAX_LENGTH = 22;
const EDGE_START_GAP = 19;
const EDGE_END_GAP = 22;
const DRAG_MARGIN = 20;
const DRAG_LABEL_MARGIN = 40;

const MODEL_INPUT_IDS = ['states', 'graph', 'ph', 'concentrations'];
const SETTINGS_INPUT_IDS = ['alpha', 'max-changes', 'budget', 'equilibrium'];
const CSV_INPUT_IDS = ['states', 'graph'];

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

let token = null;
let report = null;
let markdown = null;
let busy = false;
let revision = 0;

let statesName = 'states.csv';
let graphName = 'graph.csv';
const locks = new Set();

let candidates = [];
let candidateIndex = 0;
let witnesses = [];
let cycleIndex = 0;
let selectedEdge = null;

let positions = [];
let layoutKey = '';
let drag = null;
let networkView = { x: 0, y: 0, w: VIEW_WIDTH, h: VIEW_HEIGHT };

// Incremented whenever a CSV textarea changes, so stale file reads are ignored.
const fileReadGeneration = { states: 0, graph: 0 };

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const $ = id => document.getElementById(id);

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function formatNumber(value) {
  if (value == null) return '—';
  const magnitude = Math.abs(value);
  const useScientific = value !== 0 && (magnitude < 1e-4 || magnitude >= 1e6);
  return useScientific
    ? Number(value).toExponential(4)
    : Number(value).toLocaleString('en', { maximumSignificantDigits: 6 });
}

function yesNo(flag) {
  return flag ? 'yes' : 'no';
}

function element(tag, text, attributes = {}) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
  return node;
}

function svgElement(tag, attributes = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
  if (text != null) node.textContent = text;
  return node;
}

function cellWith(child) {
  const cell = element('td');
  cell.append(child);
  return cell;
}

function onActivate(node, handler) {
  node.addEventListener('keydown', event => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    handler();
  });
}

async function checked(response) {
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}

function selectedCandidate() {
  return candidates[candidateIndex];
}

function adjustmentFor(edgeNumber) {
  return selectedCandidate()?.value?.adjustments?.find(a => a.edge === edgeNumber);
}

// ---------------------------------------------------------------------------
// Application status
// ---------------------------------------------------------------------------

function message(text, isError = false) {
  $('message').textContent = text;
  $('message').classList.toggle('error', isError);
}

function setExportEnabled(enabled) {
  $('export-json').disabled = !enabled;
  $('export-markdown').disabled = !enabled;
}

function invalidate(clearLocks = false) {
  revision++;
  report = null;
  markdown = null;
  if (clearLocks) locks.clear();
  $('results').hidden = true;
  setExportEnabled(false);
  message('Inputs changed. Analyze the model to update the investigation.');
}

function setBusy(value) {
  busy = value;
  $('analyze').disabled = value || !token;
  $('analyze').textContent = value ? 'Analyzing…' : 'Analyze model';
  $('load-example').disabled = value || !token;
  $('results').inert = value;
  setExportEnabled(!value && Boolean(report));
}

// ---------------------------------------------------------------------------
// Server requests
// ---------------------------------------------------------------------------

async function loadExample() {
  invalidate(true);
  fileReadGeneration.states++;
  fileReadGeneration.graph++;
  const requestRevision = revision;

  try {
    const name = $('example').value;
    const data = await checked(await fetch('/api/example?name=' + encodeURIComponent(name)));
    if (requestRevision !== revision) return;

    $('states').value = data.states;
    $('graph').value = data.graph;
    statesName = data.statesName;
    graphName = data.graphName;
    $('states-file').value = '';
    $('graph-file').value = '';

    const isYiip = name === 'yiip';
    $('ph').value = isYiip ? '6.5' : '0';
    $('concentrations').value = isYiip ? 'Zn=0.000001' : '';
    $('source-note').textContent = isYiip
      ? 'Published YiiP state graph · downloaded locally with make data.'
      : 'Self-authored synthetic example · not experimental chemistry.';

    message('Example loaded. Confirm its assumptions, then analyze.');
  } catch (error) {
    message(error.message, true);
  }
}

function investigationRequestBody() {
  return new URLSearchParams({
    states: $('states').value,
    graph: $('graph').value,
    statesName,
    graphName,
    equilibrium: String($('equilibrium').checked),
    pH: $('ph').value,
    concentrations: $('concentrations').value,
    alpha: $('alpha').value,
    maxChanges: $('max-changes').value,
    budget: $('budget').value,
    locks: [...locks].sort((a, b) => a - b).join(','),
  });
}

function buildCandidates() {
  const list = [{ name: 'Original measurements', value: null }];
  if (report.weighted_repair) {
    list.push({ name: 'Weighted repair', value: report.weighted_repair });
  }
  const alternatives = report.sparse_search?.alternatives || [];
  alternatives.forEach((value, i) => list.push({ name: 'Sparse alternative ' + (i + 1), value }));
  return list;
}

function buildWitnesses() {
  const list = report.cycle_witnesses.map((value, i) => ({ name: 'Cycle ' + (i + 1), value }));
  if (report.lock_conflict) {
    list.unshift({ name: 'Incompatible locked cycle', value: report.lock_conflict });
  }
  return list;
}

async function analyze() {
  if (busy || !$('model-form').reportValidity()) return;
  const requestRevision = ++revision;
  const body = investigationRequestBody();

  setBusy(true);
  message('Calculating cycle evidence and repair candidates…');

  try {
    const response = await fetch('/api/investigate', {
      method: 'POST',
      headers: { 'X-Workspace-Token': token },
      body,
    });
    const data = await checked(response);
    if (requestRevision !== revision) return;

    report = data.report;
    markdown = data.markdown;
    candidates = buildCandidates();
    candidateIndex = report.weighted_repair ? 1 : 0;
    witnesses = buildWitnesses();
    cycleIndex = 0;
    selectedEdge = null;

    render();
    $('results').hidden = false;

    if (report.lock_conflict) {
      message('The exact locks conflict. Unlock a transition to explore repairs.', true);
    } else {
      message('Investigation updated. Select a cycle or compare candidates.');
    }
  } catch (error) {
    if (requestRevision !== revision) return;
    report = null;
    markdown = null;
    $('results').hidden = true;
    const hint = locks.size
      ? ' Use “Analyze model” to retry, or change the model to clear locks.'
      : '';
    message(error.message + hint, true);
  } finally {
    setBusy(false);
  }
}

// ---------------------------------------------------------------------------
// Report rendering
// ---------------------------------------------------------------------------

function render() {
  renderSummary();
  renderCycleOptions();
  renderCycle();
  renderCandidates();
  renderParameters();
  prepareLayout();
  drawNetwork();
  $('selection').textContent = DEFAULT_SELECTION_TEXT;
  renderAssumptions();
}

function renderSummary() {
  const stats = report.statistics;
  const status = STATUS_LABELS[report.status];

  $('summary').textContent =
    `${status}. ${report.states.length} states · ${report.edges.length} transitions · ` +
    `${stats.degrees_of_freedom} independent cycle constraints.`;
  $('summary').classList.toggle('conflict', Boolean(report.lock_conflict));

  let statistics =
    `Unconstrained measurement χ² = ${formatNumber(stats.chi_square)}; ` +
    `global p = ${formatNumber(stats.global_p)}; α = ${formatNumber(stats.alpha)}.`;
  if (stats.fisher_condition_number > ILL_CONDITIONED_THRESHOLD) {
    statistics += ` Numerically sensitive: Fisher condition number ${formatNumber(stats.fisher_condition_number)}.`;
  }
  $('statistics').textContent = statistics;
}

function renderCycleOptions() {
  const options = witnesses.map((witness, i) => {
    const flagged = witness.value.statistically_flagged ? ' · flagged' : '';
    const label = `${witness.name} · z ${formatNumber(witness.value.z_score)}${flagged}`;
    return element('option', label, { value: i });
  });
  $('cycle').replaceChildren(...options);
  $('cycle').disabled = witnesses.length === 0;
}

function renderAssumptions() {
  const items = [...report.assumptions, ...report.limitations].map(text => element('li', text));
  $('assumptions').replaceChildren(...items);

  const provenance = {
    inputs: report.inputs,
    conditions: report.conditions,
    numerical_feasibility_tolerance: report.numerical_feasibility_tolerance,
  };
  $('provenance').textContent = JSON.stringify(provenance, null, 2);
}

function cycleEquation(steps) {
  const terms = steps.map((step, i) => {
    const sign = step.signed_delta_kT < 0 ? '− ' : i > 0 ? '+ ' : '';
    return sign + formatNumber(Math.abs(step.signed_delta_kT));
  });
  return terms.join(' ');
}

function renderCycle() {
  const target = $('cycle-explanation');
  target.replaceChildren();

  const witness = witnesses[cycleIndex]?.value;
  if (!witness) {
    target.append(element('p', 'This graph has no cycles. Its chemistry cannot be validated by a cycle test.'));
    return;
  }

  const stepList = element('ol');
  for (const step of witness.steps) {
    const orientation = step.direction > 0 ? '+' : '−';
    stepList.append(element('li',
      `Edge ${step.edge}: ${step.from} → ${step.to}, ${orientation} input orientation; ` +
      `${formatNumber(step.signed_delta_kT)} kT`));
  }
  target.append(stepList);

  target.append(element('p',
    `${cycleEquation(witness.steps)} = ${formatNumber(witness.closure_kT)} kT`,
    { class: 'equation' }));

  target.append(element('p',
    `Propagated SE: ${formatNumber(witness.standard_error_kT)} kT. ` +
    `z = ${formatNumber(witness.z_score)}. ` +
    `Bonferroni-adjusted p = ${formatNumber(witness.bonferroni_p)}.`));

  const candidate = selectedCandidate();
  if (candidate.value) {
    const repairedSum = witness.steps.reduce((total, step) => {
      const adjustment = candidate.value.adjustments.find(a => a.edge === step.edge);
      return total + step.direction * adjustment.after_delta_kT;
    }, 0);
    target.append(element('p',
      `${candidate.name} cycle sum: ${formatNumber(repairedSum)} kT. ` +
      'The evidence above uses the original input measurements.'));
  }

  const isLockConflict = report.lock_conflict && cycleIndex === 0;
  if (isLockConflict) {
    target.append(element('p',
      'These exact locks cannot all hold at once. No consistent repair can preserve all of them.',
      { class: 'conflict' }));
  } else {
    target.append(element('p',
      `Numerical nonclosure: ${yesNo(witness.numerical_discrepancy)}. ` +
      `Statistically flagged: ${yesNo(witness.statistically_flagged)}.`));
  }
}

function selectCandidate(index) {
  candidateIndex = index;
  selectedEdge = null;
  $('selection').textContent = DEFAULT_SELECTION_TEXT;
  renderCandidates();
  renderCycle();
  renderParameters();
  drawNetwork();
}

function renderCandidates() {
  const body = $('repair-table').tBodies[0];
  body.replaceChildren();

  candidates.forEach((candidate, index) => {
    const isActive = index === candidateIndex;
    const row = element('tr');
    row.classList.toggle('active', isActive);

    const radio = element('input', null, { type: 'radio', name: 'candidate', 'aria-label': candidate.name });
    radio.checked = isActive;
    radio.addEventListener('change', () => selectCandidate(index));

    row.append(
      cellWith(radio),
      element('td', candidate.name),
      element('td', candidate.value ? candidate.value.changed_count : '0'),
      element('td', candidate.value ? formatNumber(candidate.value.measurement_chi_square) : '—'),
    );
    body.append(row);
  });

  renderSearchStatus();
}

function renderSearchStatus() {
  const search = report.sparse_search;
  if (!search) {
    $('search-status').textContent = 'Repair search unavailable while the exact locks conflict.';
    return;
  }
  $('search-status').textContent =
    `${search.status}. Supports evaluated: ${search.supports_tested}/${search.budget}. ` +
    `Minimum change count certified: ${yesNo(search.minimum_cardinality_certified)}; ` +
    `all supports at that size evaluated: ${yesNo(search.minimum_level_complete)}; ` +
    `budget exhausted: ${yesNo(search.budget_exhausted)}.`;
}

function toggleLock(edgeNumber, locked) {
  if (locked) locks.add(edgeNumber);
  else locks.delete(edgeNumber);
  analyze();
}

function renderParameters() {
  const body = $('parameters').tBodies[0];
  body.replaceChildren();

  for (const edge of report.edges) {
    const adjustment = adjustmentFor(edge.edge);
    const row = element('tr', null, { 'data-edge': edge.edge });
    row.classList.toggle('highlight', selectedEdge === edge.edge);

    const lockBox = element('input', null, {
      type: 'checkbox',
      'aria-label': `Lock edge ${edge.edge}: ${edge.from} to ${edge.to}`,
    });
    lockBox.checked = locks.has(edge.edge);
    lockBox.addEventListener('change', () => toggleLock(edge.edge, lockBox.checked));

    const inspectButton = element('button', `${edge.from} → ${edge.to}`, { 'aria-label': `Inspect edge ${edge.edge}` });
    inspectButton.addEventListener('click', () => selectEdge(edge.edge));

    const candidateValue = adjustment ? adjustment.repaired_parameter : edge.original_parameter;
    const candidateCell = element('td', formatNumber(candidateValue));
    candidateCell.classList.toggle('changed', Boolean(adjustment?.changed));

    row.append(
      cellWith(lockBox),
      element('td', edge.edge),
      cellWith(inspectButton),
      element('td', formatNumber(edge.original_parameter)),
      element('td', edge.parameter_unit),
      candidateCell,
      element('td', adjustment ? formatNumber(adjustment.adjustment_in_measurement_sigmas) : '—'),
      element('td', adjustment ? formatNumber(adjustment.conditional_delta_standard_error_kT) : '—'),
    );
    body.append(row);
  }

  $('unlock-all').disabled = locks.size === 0;
}

function selectEdge(edgeNumber) {
  selectedEdge = edgeNumber;
  const edge = report.edges.find(e => e.edge === edgeNumber);
  const unit = edge.parameter_unit;

  $('selection').textContent =
    `Edge ${edgeNumber}: ${edge.from} → ${edge.to}. ` +
    `Input parameter ${formatNumber(edge.original_parameter)} ${unit}, ` +
    `variance ${formatNumber(edge.original_parameter_variance)} ${unit}². ` +
    `Converted difference ${formatNumber(edge.input_delta_kT)} kT. ` +
    (locks.has(edgeNumber) ? 'Locked.' : 'Unlocked.');

  renderParameters();
  drawNetwork();
}

function inspectState(index) {
  const state = report.states[index];
  const candidate = selectedCandidate();
  const energy = candidate?.value?.state_energies_kT[index] ?? state.unconstrained_energy_kT;
  const energyKind = candidate.value ? 'Candidate' : 'Unconstrained fitted';

  $('selection').textContent =
    `State ${state.name}. ${energyKind} energy: ${formatNumber(energy)} kT. ` +
    `Unconstrained equilibrium population: ${formatNumber(state.unconstrained_probability)}.`;
}

// ---------------------------------------------------------------------------
// Network layout (force-directed, recomputed only when the topology changes)
// ---------------------------------------------------------------------------

function addPairForce(forces, a, b, dx, dy, distance, strength) {
  const fx = dx / distance * strength;
  const fy = dy / distance * strength;
  forces[a].x += fx;
  forces[a].y += fy;
  forces[b].x -= fx;
  forces[b].y -= fy;
}

function initialCircleLayout(states) {
  const n = states.length;
  return states.map((state, i) => {
    const angle = i / n * 2 * Math.PI - Math.PI / 2;
    return {
      name: state.name,
      x: LAYOUT.centerX + LAYOUT.initialRadiusX * Math.cos(angle),
      y: LAYOUT.centerY + LAYOUT.initialRadiusY * Math.sin(angle),
    };
  });
}

function relaxLayout(links) {
  const n = positions.length;
  const springLength = Math.max(90, 240 - n * 3);

  for (let iteration = 0; iteration < LAYOUT.iterations; iteration++) {
    const forces = positions.map(() => ({ x: 0, y: 0 }));

    for (let a = 0; a < n; a++) {
      for (let b = a + 1; b < n; b++) {
        const dx = positions[a].x - positions[b].x;
        const dy = positions[a].y - positions[b].y;
        const distance = Math.max(10, Math.hypot(dx, dy));
        const strength = LAYOUT.repulsion / (distance * distance);
        addPairForce(forces, a, b, dx, dy, distance, strength);
      }
    }

    for (const [a, b] of links) {
      const dx = positions[b].x - positions[a].x;
      const dy = positions[b].y - positions[a].y;
      const distance = Math.max(1, Math.hypot(dx, dy));
      const strength = (distance - springLength) * LAYOUT.springStiffness;
      addPairForce(forces, a, b, dx, dy, distance, strength);
    }

    positions.forEach((p, i) => {
      const stepX = forces[i].x + (LAYOUT.centerX - p.x) * LAYOUT.gravity;
      const stepY = forces[i].y + (LAYOUT.centerY - p.y) * LAYOUT.gravity;
      p.x = clamp(p.x + clamp(stepX, -LAYOUT.maxStep, LAYOUT.maxStep), LAYOUT.minX, LAYOUT.maxX);
      p.y = clamp(p.y + clamp(stepY, -LAYOUT.maxStep, LAYOUT.maxStep), LAYOUT.minY, LAYOUT.maxY);
    });
  }
}

function fitViewToPositions() {
  const xs = positions.map(p => p.x);
  const ys = positions.map(p => p.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);

  const w = Math.max(
    LAYOUT.minFitWidth,
    maxX - minX + LAYOUT.fitPadding,
    (maxY - minY + LAYOUT.fitPadding) / VIEW_ASPECT,
  );
  const h = w * VIEW_ASPECT;
  networkView = { x: (minX + maxX - w) / 2, y: (minY + maxY - h) / 2, w, h };
}

function prepareLayout(force = false) {
  const key = JSON.stringify([report.states.map(s => s.name), report.edges.map(e => [e.from, e.to])]);
  if (!force && key === layoutKey) return;
  layoutKey = key;

  positions = initialCircleLayout(report.states);
  const indexByName = new Map(positions.map((p, i) => [p.name, i]));
  const links = report.edges.map(e => [indexByName.get(e.from), indexByName.get(e.to)]);
  relaxLayout(links);
  fitViewToPositions();
}

// ---------------------------------------------------------------------------
// Network drawing
// ---------------------------------------------------------------------------

function arrowMarkerDefs() {
  const defs = svgElement('defs');
  const marker = svgElement('marker', {
    id: 'arrow',
    viewBox: '0 0 10 10',
    refX: 9,
    refY: 5,
    markerWidth: 5,
    markerHeight: 5,
    orient: 'auto-start-reverse',
  });
  marker.append(svgElement('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: 'context-stroke' }));
  defs.append(marker);
  return defs;
}

function edgeColor(edgeNumber, cycleEdges, changed) {
  if (selectedEdge === edgeNumber) return EDGE_COLORS.selected;
  if (cycleEdges.has(edgeNumber)) return EDGE_COLORS.cycle;
  if (locks.has(edgeNumber)) return EDGE_COLORS.locked;
  if (changed) return EDGE_COLORS.changed;
  return EDGE_COLORS.default;
}

function drawEdge(edge, points, cycleEdges) {
  const from = points.get(edge.from);
  const to = points.get(edge.to);
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const length = Math.max(1, Math.hypot(dx, dy));

  const start = { x: from.x + dx / length * EDGE_START_GAP, y: from.y + dy / length * EDGE_START_GAP };
  const end = { x: to.x - dx / length * EDGE_END_GAP, y: to.y - dy / length * EDGE_END_GAP };
  const mid = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 };
  const path = `M ${start.x} ${start.y} L ${end.x} ${end.y}`;

  const adjustment = adjustmentFor(edge.edge);
  const value = adjustment?.after_delta_kT ?? edge.input_delta_kT;
  const isLocked = locks.has(edge.edge);
  const color = edgeColor(edge.edge, cycleEdges, adjustment?.changed);

  const group = svgElement('g', {
    class: 'edge',
    tabindex: 0,
    role: 'button',
    'aria-label': `Edge ${edge.edge}: ${edge.from} to ${edge.to}, ${formatNumber(value)} kT`,
  });
  group.append(svgElement('title', {},
    `Edge ${edge.edge}: ${edge.from} → ${edge.to}, ${formatNumber(value)} kT ` +
    `(${selectedCandidate().name})${isLocked ? ', locked' : ''}`));
  group.append(
    svgElement('path', { d: path, class: 'hit' }),
    svgElement('path', { d: path, stroke: color, 'marker-end': 'url(#arrow)' }),
  );

  const showLabel = $('show-values').checked || cycleEdges.has(edge.edge) || selectedEdge === edge.edge;
  if (showLabel) {
    const labelX = (start.x + 2 * mid.x + end.x) / 4;
    const labelY = (start.y + 2 * mid.y + end.y) / 4 - 6;
    group.append(svgElement('text', { x: labelX, y: labelY, 'text-anchor': 'middle' },
      `#${edge.edge} ${formatNumber(value)}${isLocked ? ' [L]' : ''}`));
  }

  group.addEventListener('click', () => selectEdge(edge.edge));
  onActivate(group, () => selectEdge(edge.edge));
  return group;
}

function truncateLabel(name) {
  return name.length > NODE_LABEL_MAX_LENGTH ? name.slice(0, NODE_LABEL_MAX_LENGTH - 1) + '…' : name;
}

function drawNode(position, index, svg) {
  const group = svgElement('g', {
    class: 'node',
    'data-node': index,
    transform: `translate(${position.x} ${position.y})`,
    tabindex: 0,
    role: 'button',
    'aria-label': `State ${position.name}`,
  });
  group.append(
    svgElement('title', {}, position.name),
    svgElement('circle', { r: NODE_RADIUS }),
    svgElement('text', { y: NODE_LABEL_OFFSET, 'text-anchor': 'middle' }, truncateLabel(position.name)),
  );

  group.addEventListener('pointerdown', event => {
    if (event.button !== 0) return;
    event.stopPropagation();
    event.preventDefault();
    drag = { index };
    svg.setPointerCapture(event.pointerId);
    inspectState(index);
  });
  onActivate(group, () => inspectState(index));
  return group;
}

function drawNetwork() {
  if (!report) return;

  $('graph-caption').textContent =
    `Showing ${selectedCandidate().name.toLowerCase()} energy differences (kT). ` +
    'Cycle evidence uses the original measurements.';

  const svg = $('network');
  svg.replaceChildren();
  svg.setAttribute('viewBox', `${networkView.x} ${networkView.y} ${networkView.w} ${networkView.h}`);
  svg.append(arrowMarkerDefs());

  const points = new Map(positions.map(p => [p.name, p]));
  const cycleEdges = new Set(witnesses[cycleIndex]?.value.steps.map(s => s.edge) || []);

  for (const edge of report.edges) svg.append(drawEdge(edge, points, cycleEdges));
  positions.forEach((position, i) => svg.append(drawNode(position, i, svg)));
}

// ---------------------------------------------------------------------------
// Network interaction: drag nodes, pan background, zoom
// ---------------------------------------------------------------------------

function networkPoint(event) {
  const screenToSvg = $('network').getScreenCTM().inverse();
  return new DOMPoint(event.clientX, event.clientY).matrixTransform(screenToSvg);
}

function zoom(factor) {
  const newWidth = networkView.w * factor;
  if (newWidth < MIN_VIEW_WIDTH || newWidth > MAX_VIEW_WIDTH) return;
  networkView.x += networkView.w * (1 - factor) / 2;
  networkView.y += networkView.h * (1 - factor) / 2;
  networkView.w *= factor;
  networkView.h *= factor;
  drawNetwork();
}

$('network').addEventListener('pointerdown', event => {
  if (event.button !== 0 || event.target !== $('network')) return;
  event.preventDefault();
  drag = { point: networkPoint(event) };
  $('network').setPointerCapture(event.pointerId);
});

$('network').addEventListener('pointermove', event => {
  if (drag == null) return;
  const point = networkPoint(event);

  if (drag.index != null) {
    const node = positions[drag.index];
    node.x = clamp(point.x, networkView.x + DRAG_MARGIN, networkView.x + networkView.w - DRAG_MARGIN);
    node.y = clamp(point.y, networkView.y + DRAG_MARGIN, networkView.y + networkView.h - DRAG_LABEL_MARGIN);
  } else {
    networkView.x += drag.point.x - point.x;
    networkView.y += drag.point.y - point.y;
  }
  drawNetwork();
});

for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  $('network').addEventListener(type, () => { drag = null; });
}

$('zoom-in').addEventListener('click', () => zoom(0.8));
$('zoom-out').addEventListener('click', () => zoom(1.25));
$('reset-layout').addEventListener('click', () => {
  prepareLayout(true);
  drawNetwork();
});
$('show-values').addEventListener('change', drawNetwork);
$('cycle').addEventListener('change', () => {
  cycleIndex = Number($('cycle').value);
  renderCycle();
  drawNetwork();
});

// ---------------------------------------------------------------------------
// Model inputs
// ---------------------------------------------------------------------------

$('model-form').addEventListener('submit', event => {
  event.preventDefault();
  analyze();
});
$('load-example').addEventListener('click', loadExample);
$('unlock-all').addEventListener('click', () => {
  locks.clear();
  analyze();
});

for (const id of [...MODEL_INPUT_IDS, ...SETTINGS_INPUT_IDS]) {
  $(id).addEventListener('input', () => {
    invalidate(MODEL_INPUT_IDS.includes(id));
    if (CSV_INPUT_IDS.includes(id)) {
      fileReadGeneration[id]++;
      $('source-note').textContent = 'Edited model · assumptions are supplied by the operator.';
    }
  });
}

for (const id of CSV_INPUT_IDS) {
  $(id + '-file').addEventListener('change', async event => {
    const file = event.target.files[0];
    if (!file) return;
    invalidate(true);
    const generation = ++fileReadGeneration[id];

    try {
      if (file.size > MAX_UPLOAD_BYTES) {
        throw new Error('CSV must be smaller than 450 kB for the visual workspace.');
      }
      const text = await file.text();
      if (generation !== fileReadGeneration[id]) return;

      invalidate(true);
      $(id).value = text;
      if (id === 'states') statesName = file.name;
      else graphName = file.name;
      $('source-note').textContent = 'User-supplied CSV · processed locally; files are not retained by the server.';
    } catch (error) {
      message(error.message, true);
    }
  });
}

// ---------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------

function download(content, type, filename) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = element('a', null, { href: url, download: filename });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

$('export-json').addEventListener('click', () => {
  if (!report || busy) return;
  download(JSON.stringify(report, null, 2) + '\n', 'application/json', 'investigation.json');
});

$('export-markdown').addEventListener('click', () => {
  if (!report || busy) return;
  download(markdown, 'text/markdown', 'investigation.md');
});

// ---------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------

(async () => {
  try {
    const config = await checked(await fetch('/api/config'));
    token = config.token;
    if (config.examples.includes('yiip')) {
      $('example').append(element('option', 'Published · YiiP (32 states)', { value: 'yiip' }));
    }
    setBusy(false);
    await loadExample();
  } catch (error) {
    message('Could not connect to the local solver: ' + error.message, true);
  }
})();
