const PAGE_SIZE = 100;
const BULK_CONCURRENCY = 6;
const METADATA_DB_NAME = "NicoCacheWebMetadata";
const METADATA_DB_VERSION = 1;
const METADATA_STORE_NAME = "videoMetadata";

const asFiniteNumber = (value, fallback = 0) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
};

export const getBaseVideoId = (cacheId) =>
  String(cacheId).match(/^([a-z]{2}\d+)/i)?.[1]?.toLowerCase() ?? String(cacheId);

export const parseCacheQuality = (cacheId) => {
  const value = String(cacheId);
  const match = value.match(/\[(\d+)p(?:-[^,\]]+)?(?:,(\d+))?\]/i);
  const height = match ? asFiniteNumber(match[1]) : 0;
  const audioBitrate = match ? asFiniteNumber(match[2]) : 0;
  const parts = [];
  if (height > 0) parts.push(`${height}p`);
  if (audioBitrate > 0) parts.push(`${audioBitrate} kbps`);
  if (parts.length === 0 && /low/i.test(value)) parts.push("low");
  return { height, audioBitrate, label: parts.join(" · ") || "unknown" };
};

const normalizeCollection = (collection, temporary) =>
  Object.entries(collection && typeof collection === "object" ? collection : {})
    .filter(([, value]) => Array.isArray(value))
    .map(([cacheId, value]) => {
      const quality = parseCacheQuality(cacheId);
      const size = asFiniteNumber(value[temporary ? 1 : 2]);
      const expectedSize = temporary ? asFiniteNumber(value[2]) : size;
      return {
        cacheId,
        baseId: getBaseVideoId(cacheId),
        title: typeof value[0] === "string" && value[0].trim()
          ? value[0]
          : cacheId,
        subFolder: temporary || typeof value[1] !== "string" ? "" : value[1],
        size,
        expectedSize,
        downloading: temporary && value[3] === true,
        updatedAt: asFiniteNumber(value[temporary ? 4 : 3]) * 1000,
        temporary,
        quality: quality.label,
        qualityHeight: quality.height,
        audioBitrate: quality.audioBitrate,
        availabilityStatus: "unknown",
      };
    });

export const normalizeCacheResponse = (payload) => {
  const complete = normalizeCollection(payload?.complete, false);
  const temporary = normalizeCollection(payload?.temporary, true);
  const merged = new Map(complete.map((entry) => [entry.cacheId, entry]));
  for (const entry of temporary) merged.set(entry.cacheId, entry);
  return [...merged.values()];
};

export const formatStorageLocation = (entry, cacheRootLabel) =>
  typeof entry?.subFolder === "string" && entry.subFolder.trim()
    ? entry.subFolder
    : cacheRootLabel;

const idParts = (entry) => {
  const prefix = entry.baseId.slice(0, 2).toLowerCase();
  const priority = { nm: 1, sm: 2, so: 3 }[prefix] ?? 4;
  const number = asFiniteNumber(entry.baseId.match(/\d+/)?.[0]);
  return { priority, number };
};

const compareId = (left, right) => {
  const a = idParts(left);
  const b = idParts(right);
  return a.priority - b.priority
    || a.number - b.number
    || left.cacheId.localeCompare(right.cacheId, "ja", { numeric: true });
};

const availabilityPriority = (entry) => {
  if (["deleted", "private", "unavailable"].includes(entry.availabilityStatus)) return 0;
  if (entry.availabilityStatus === "unknown") return 1;
  return 2;
};

export const filterAndSortEntries = (entries, options = {}) => {
  const query = String(options.query ?? "").trim().toLocaleLowerCase();
  const keywords = query.split(/\s+/).filter(Boolean);
  const state = options.state ?? "all";
  const quality = options.quality ?? "all";
  const sort = options.sort ?? "id";
  const direction = options.direction === "desc" ? -1 : 1;

  const filtered = entries.filter((entry) => {
    const searchable = [
      entry.cacheId, entry.baseId, entry.title, entry.subFolder, entry.quality,
    ].join(" ").toLocaleLowerCase();
    if (!keywords.every((keyword) => searchable.includes(keyword))) return false;
    if (state === "complete" && entry.temporary) return false;
    if (state === "temporary" && !entry.temporary) return false;
    if (state === "unavailable"
      && !["deleted", "private", "unavailable"].includes(entry.availabilityStatus)) {
      return false;
    }
    if (quality === "hd" && entry.qualityHeight < 720) return false;
    if (quality === "sd" && (entry.qualityHeight < 480 || entry.qualityHeight >= 720)) return false;
    if (quality === "low" && (entry.qualityHeight <= 0 || entry.qualityHeight >= 480)) return false;
    if (quality === "unknown" && entry.qualityHeight !== 0) return false;
    return true;
  });

  const compare = (left, right) => {
    if (sort === "title") return left.title.localeCompare(right.title, "ja");
    if (sort === "quality") return left.qualityHeight - right.qualityHeight;
    if (sort === "availability") {
      return availabilityPriority(left) - availabilityPriority(right) || compareId(left, right);
    }
    if (sort === "size") return left.size - right.size;
    if (sort === "updated") return left.updatedAt - right.updatedAt;
    return compareId(left, right);
  };
  return filtered.sort((left, right) => compare(left, right) * direction);
};

const thumbnailUrl = (videoId) => {
  const numericId = String(videoId).match(/\d+/)?.[0];
  return numericId
    ? `https://nicovideo.cdn.nimg.jp/thumbnails/${numericId}/${numericId}`
    : "";
};

const isMetadata = (value) => value && typeof value === "object"
  && value.schemaVersion === 1
  && typeof value.videoId === "string"
  && ["unknown", "available", "deleted", "private", "unavailable"]
    .includes(value.availabilityStatus)
  && typeof value.updatedAt === "number";

class MetadataStore {
  constructor() {
    this.databasePromise = null;
    this.disabled = typeof indexedDB === "undefined";
  }

  async open() {
    if (this.disabled) return null;
    if (!this.databasePromise) {
      this.databasePromise = this.openOnce().catch(async () => {
        await this.deleteDatabase();
        return this.openOnce();
      }).catch(() => {
        this.disabled = true;
        return null;
      });
    }
    return this.databasePromise;
  }

  openOnce() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(METADATA_DB_NAME, METADATA_DB_VERSION);
      request.onerror = () => reject(request.error ?? new Error("IndexedDB open failed"));
      request.onupgradeneeded = () => {
        const database = request.result;
        if (!database.objectStoreNames.contains(METADATA_STORE_NAME)) {
          database.createObjectStore(METADATA_STORE_NAME, { keyPath: "videoId" });
        }
      };
      request.onsuccess = () => {
        if (!request.result.objectStoreNames.contains(METADATA_STORE_NAME)) {
          request.result.close();
          reject(new Error("IndexedDB object store is missing"));
          return;
        }
        resolve(request.result);
      };
    });
  }

  deleteDatabase() {
    return new Promise((resolve) => {
      const request = indexedDB.deleteDatabase(METADATA_DB_NAME);
      request.onsuccess = request.onerror = request.onblocked = () => resolve();
    });
  }

  async load(videoIds) {
    const database = await this.open();
    if (!database) return new Map();
    const transaction = database.transaction(METADATA_STORE_NAME, "readonly");
    const store = transaction.objectStore(METADATA_STORE_NAME);
    const records = await Promise.all([...new Set(videoIds)].map((videoId) =>
      new Promise((resolve) => {
        const request = store.get(videoId);
        request.onerror = () => resolve(null);
        request.onsuccess = () => resolve(isMetadata(request.result) ? request.result : null);
      })));
    return new Map(records.filter(Boolean).map((record) => [record.videoId, record]));
  }

  async save(records) {
    const validRecords = records.filter(isMetadata);
    if (validRecords.length === 0) return;
    const database = await this.open();
    if (!database) return;
    await new Promise((resolve, reject) => {
      const transaction = database.transaction(METADATA_STORE_NAME, "readwrite");
      const store = transaction.objectStore(METADATA_STORE_NAME);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB save failed"));
      for (const record of validRecords) store.put(record);
    });
  }
}

const runWithConcurrency = async (items, concurrency, worker) => {
  let nextIndex = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (nextIndex < items.length) {
      const item = items[nextIndex];
      nextIndex += 1;
      await worker(item);
    }
  }));
};

const formatDate = (milliseconds) => milliseconds > 0
  ? new Date(milliseconds).toLocaleString()
  : "—";

const metadataRecord = (metadata) => ({
  ...metadata,
  schemaVersion: 1,
  updatedAt: Date.now(),
});

const applyMetadata = (entries, metadataMap) => {
  for (const entry of entries) {
    const metadata = metadataMap.get(entry.baseId);
    if (!metadata) continue;
    entry.availabilityStatus = metadata.availabilityStatus;
    entry.availabilityErrorCode = metadata.errorCode;
    entry.metadata = metadata;
    if ((!entry.title || entry.title === entry.cacheId) && metadata.title) {
      entry.title = metadata.title;
    }
  }
};

export async function renderCacheManager({ app, fetchJson, t, escapeHtml, formatBytes }) {
  const metadataStore = new MetadataStore();
  let entries = [];
  let metadataMap = new Map();
  let visibleCount = PAGE_SIZE;

  app.innerHTML = `
    <section class="cache-heading">
      <div><h1>${t("cacheEntries")}</h1><p class="lead">${t("cacheManagerLead")}</p></div>
      <button id="cache-refresh" type="button">${t("refresh")}</button>
    </section>
    <section class="panel cache-controls" aria-label="${t("cacheControls")}">
      <label class="search-control"><span>${t("search")}</span>
        <input id="cache-search" type="search" autocomplete="off" placeholder="${t("searchPlaceholder")}">
      </label>
      <label><span>${t("state")}</span><select id="cache-state">
        <option value="all">${t("all")}</option><option value="complete">${t("complete")}</option>
        <option value="temporary">${t("temporary")}</option><option value="unavailable">${t("unavailable")}</option>
      </select></label>
      <label><span>${t("quality")}</span><select id="cache-quality">
        <option value="all">${t("all")}</option><option value="hd">HD (720p+)</option>
        <option value="sd">SD (480p)</option><option value="low">${t("lowQuality")}</option>
        <option value="unknown">${t("unknown")}</option>
      </select></label>
      <label><span>${t("sort")}</span><select id="cache-sort">
        <option value="id">ID</option><option value="title">${t("title")}</option>
        <option value="quality">${t("quality")}</option><option value="availability">${t("availability")}</option>
        <option value="size">${t("size")}</option><option value="updated">${t("updated")}</option>
      </select></label>
      <div class="control-buttons">
        <button id="cache-direction" type="button" data-direction="asc" aria-label="${t("ascending")}">↑</button>
        <button id="cache-reset" type="button">${t("reset")}</button>
        <button id="check-availability" type="button">${t("checkAvailability")}</button>
        <button id="delete-temporary" class="danger" type="button">${t("deleteTemporary")}</button>
      </div>
    </section>
    <div class="cache-summary"><strong id="cache-count"></strong><span id="cache-progress" role="status"></span></div>
    <section id="cache-grid" class="cache-grid" aria-live="polite"></section>
    <div class="load-more-wrap"><button id="cache-load-more" type="button" hidden>${t("loadMore")}</button></div>
    <dialog id="cache-detail-dialog" class="cache-dialog"><div id="cache-detail"></div>
      <form method="dialog"><button type="submit">${t("close")}</button></form>
    </dialog>`;

  const controls = {
    search: app.querySelector("#cache-search"),
    state: app.querySelector("#cache-state"),
    quality: app.querySelector("#cache-quality"),
    sort: app.querySelector("#cache-sort"),
    direction: app.querySelector("#cache-direction"),
    count: app.querySelector("#cache-count"),
    progress: app.querySelector("#cache-progress"),
    grid: app.querySelector("#cache-grid"),
    loadMore: app.querySelector("#cache-load-more"),
    dialog: app.querySelector("#cache-detail-dialog"),
    detail: app.querySelector("#cache-detail"),
  };

  const currentOptions = () => ({
    query: controls.search.value,
    state: controls.state.value,
    quality: controls.quality.value,
    sort: controls.sort.value,
    direction: controls.direction.dataset.direction,
  });

  const renderEntries = () => {
    const filtered = filterAndSortEntries(entries, currentOptions());
    controls.count.textContent = filtered.length === entries.length
      ? `${entries.length.toLocaleString()} ${t("items")}`
      : `${filtered.length.toLocaleString()} / ${entries.length.toLocaleString()} ${t("items")}`;
    const shown = filtered.slice(0, visibleCount);
    controls.grid.innerHTML = shown.map((entry) => {
      const unavailable = ["deleted", "private", "unavailable"].includes(entry.availabilityStatus);
      const percent = entry.temporary && entry.expectedSize > 0
        ? Math.min(100, Math.round(entry.size / entry.expectedSize * 100))
        : null;
      const statusText = entry.temporary
        ? entry.downloading ? t("downloading") : t("temporary")
        : t("complete");
      const thumbnail = entry.metadata?.thumbnailUrl || thumbnailUrl(entry.baseId);
      return `<article class="cache-card${unavailable ? " is-unavailable" : ""}" data-cache-id="${escapeHtml(entry.cacheId)}">
        <div class="cache-thumbnail">
          <img src="${escapeHtml(thumbnail)}" loading="lazy" alt="" referrerpolicy="no-referrer">
          <span class="thumbnail-fallback" hidden>${escapeHtml(entry.baseId)}</span>
        </div>
        <div class="cache-card-body">
          <div class="cache-badges"><span>${escapeHtml(statusText)}</span><span>${escapeHtml(entry.quality)}</span>
            ${unavailable ? `<span class="danger-badge">${t("unavailable")}</span>` : ""}</div>
          <h2 title="${escapeHtml(entry.title)}">${escapeHtml(entry.title)}</h2>
          <code>${escapeHtml(entry.cacheId)}</code>
          <dl><div><dt>${t("size")}</dt><dd>${formatBytes(entry.size)}</dd></div>
            <div><dt>${t("folder")}</dt><dd>${escapeHtml(formatStorageLocation(entry, t("cacheRoot")))}</dd></div>
            <div><dt>${t("updated")}</dt><dd>${escapeHtml(formatDate(entry.updatedAt))}</dd></div></dl>
          ${percent === null ? "" : `<div class="cache-download-progress"><progress max="100" value="${percent}"></progress><span>${percent}%</span></div>`}
          <div class="cache-card-actions">
            <div class="cache-action-row primary-actions">
              <a class="button${entry.temporary ? " disabled" : ""}" ${entry.temporary ? "aria-disabled=\"true\"" : `href="/api/v1/videos/${encodeURIComponent(entry.baseId)}/media" target="_blank" rel="noopener"`}>${t("play")}</a>
              <button type="button" data-action="details">${t("details")}</button>
            </div>
            <div class="cache-action-row secondary-actions">
              <a class="button${entry.temporary ? " disabled" : ""}" ${entry.temporary ? "aria-disabled=\"true\"" : `href="/api/v1/videos/${encodeURIComponent(entry.baseId)}/exports/video"`}>${t("exportVideo")}</a>
              <a class="button${entry.temporary ? " disabled" : ""}" ${entry.temporary ? "aria-disabled=\"true\"" : `href="/api/v1/videos/${encodeURIComponent(entry.baseId)}/exports/audio"`}>${t("exportAudio")}</a>
              <button class="danger" type="button" data-action="delete">${t("delete")}</button>
            </div>
          </div>
        </div>
      </article>`;
    }).join("") || `<p class="panel empty-state">${t("noEntries")}</p>`;
    controls.loadMore.hidden = shown.length >= filtered.length;
    controls.grid.querySelectorAll("img").forEach((image) => {
      image.addEventListener("error", () => {
        image.hidden = true;
        image.nextElementSibling.hidden = false;
      }, { once: true });
    });
  };

  const setProgress = (message = "") => {
    controls.progress.textContent = message;
  };

  const refresh = async () => {
    setProgress(t("loading"));
    const payload = await fetchJson("/cache-entries");
    entries = normalizeCacheResponse(payload);
    metadataMap = await metadataStore.load(entries.map((entry) => entry.baseId));
    applyMetadata(entries, metadataMap);
    visibleCount = PAGE_SIZE;
    renderEntries();
    setProgress("");
  };

  const fetchMetadata = async (videoId) => {
    const metadata = metadataRecord(await fetchJson(
      `/videos/${encodeURIComponent(videoId)}/metadata`,
    ));
    metadataMap.set(videoId, metadata);
    await metadataStore.save([metadata]);
    applyMetadata(entries, metadataMap);
    return metadata;
  };

  const showDetails = async (entry) => {
    let metadata = metadataMap.get(entry.baseId);
    if (!metadata) {
      controls.detail.innerHTML = `<p>${t("loading")}</p>`;
      controls.dialog.showModal();
      try {
        metadata = await fetchMetadata(entry.baseId);
        renderEntries();
      } catch (error) {
        metadata = { availabilityStatus: "unknown", message: error.message };
      }
    }
    const tags = Array.isArray(metadata.tags)
      ? metadata.tags.map((tag) => `<span>${escapeHtml(tag)}</span>`).join("")
      : "";
    controls.detail.innerHTML = `<h2>${escapeHtml(metadata.title || entry.title)}</h2>
      <p><code>${escapeHtml(entry.cacheId)}</code></p>
      <dl class="detail-list">
        <div><dt>${t("state")}</dt><dd>${escapeHtml(entry.temporary ? t("temporary") : t("complete"))}</dd></div>
        <div><dt>${t("availability")}</dt><dd>${escapeHtml(metadata.availabilityStatus || t("unknown"))}</dd></div>
        <div><dt>${t("quality")}</dt><dd>${escapeHtml(entry.quality)}</dd></div>
        <div><dt>${t("size")}</dt><dd>${formatBytes(entry.size)}</dd></div>
        <div><dt>${t("folder")}</dt><dd>${escapeHtml(formatStorageLocation(entry, t("cacheRoot")))}</dd></div>
        <div><dt>${t("author")}</dt><dd>${escapeHtml(metadata.author || "—")}</dd></div>
        <div><dt>${t("duration")}</dt><dd>${escapeHtml(metadata.duration || "—")}</dd></div>
        <div><dt>${t("views")}</dt><dd>${asFiniteNumber(metadata.viewCount).toLocaleString()}</dd></div>
        <div><dt>${t("comments")}</dt><dd>${asFiniteNumber(metadata.commentCount).toLocaleString()}</dd></div>
        <div><dt>${t("mylists")}</dt><dd>${asFiniteNumber(metadata.mylistCount).toLocaleString()}</dd></div>
      </dl>${metadata.message ? `<p class="error">${escapeHtml(metadata.message)}</p>` : ""}
      ${tags ? `<div class="tag-list">${tags}</div>` : ""}`;
    if (!controls.dialog.open) controls.dialog.showModal();
  };

  const deleteEntry = async (entry) => {
    if (!confirm(`${t("confirmDelete")}\n${entry.cacheId}\n${entry.title}`)) return;
    const path = entry.temporary && entry.downloading
      ? `/videos/${encodeURIComponent(entry.baseId)}/temporary-cache-entries`
      : `/${entry.temporary ? "temporary-cache-entries" : "cache-entries"}/${encodeURIComponent(entry.cacheId)}`;
    setProgress(t("deleting"));
    const result = await fetchJson(path, { method: "DELETE" });
    alert(t(result.status === "scheduled" ? "deleteScheduled" : "deleteComplete"));
    await refresh();
  };

  const checkAvailability = async () => {
    const videoIds = [...new Set(entries.map((entry) => entry.baseId))];
    if (videoIds.length === 0 || !confirm(`${t("confirmAvailability")}\n${videoIds.length.toLocaleString()} ${t("items")}`)) return;
    let completed = 0;
    let failed = 0;
    const records = [];
    await runWithConcurrency(videoIds, BULK_CONCURRENCY, async (videoId) => {
      try {
        const metadata = metadataRecord(await fetchJson(
          `/videos/${encodeURIComponent(videoId)}/metadata`,
        ));
        records.push(metadata);
        metadataMap.set(videoId, metadata);
      } catch {
        failed += 1;
      } finally {
        completed += 1;
        setProgress(`${t("checkingAvailability")} ${completed.toLocaleString()} / ${videoIds.length.toLocaleString()}`);
      }
    });
    await metadataStore.save(records);
    applyMetadata(entries, metadataMap);
    renderEntries();
    setProgress("");
    alert(`${t("availabilityComplete")}\n${t("success")}: ${records.length.toLocaleString()}\n${t("failed")}: ${failed.toLocaleString()}`);
  };

  const deleteTemporary = async () => {
    const videoIds = [...new Set(entries.filter((entry) => entry.temporary).map((entry) => entry.baseId))];
    if (videoIds.length === 0) {
      alert(t("noTemporary"));
      return;
    }
    if (!confirm(`${t("confirmDeleteTemporary")}\n${videoIds.length.toLocaleString()} ${t("items")}`)) return;
    const totals = { deleted: 0, scheduled: 0, not_found: 0, failed: 0 };
    let completed = 0;
    await runWithConcurrency(videoIds, BULK_CONCURRENCY, async (videoId) => {
      try {
        const result = await fetchJson(
          `/videos/${encodeURIComponent(videoId)}/temporary-cache-entries`,
          { method: "DELETE" },
        );
        if (Object.hasOwn(totals, result.status)) totals[result.status] += 1;
        else totals.failed += 1;
      } catch {
        totals.failed += 1;
      } finally {
        completed += 1;
        setProgress(`${t("deleting")} ${completed.toLocaleString()} / ${videoIds.length.toLocaleString()}`);
      }
    });
    alert(`${t("deleteComplete")}\n${t("deleted")}: ${totals.deleted}\n${t("scheduled")}: ${totals.scheduled}\n${t("notFound")}: ${totals.not_found}\n${t("failed")}: ${totals.failed}`);
    await refresh();
  };

  const updateFromControls = () => {
    visibleCount = PAGE_SIZE;
    renderEntries();
  };
  controls.search.addEventListener("input", updateFromControls);
  controls.state.addEventListener("change", updateFromControls);
  controls.quality.addEventListener("change", updateFromControls);
  controls.sort.addEventListener("change", updateFromControls);
  controls.direction.addEventListener("click", () => {
    const descending = controls.direction.dataset.direction === "desc";
    controls.direction.dataset.direction = descending ? "asc" : "desc";
    controls.direction.textContent = descending ? "↑" : "↓";
    controls.direction.setAttribute("aria-label", t(descending ? "ascending" : "descending"));
    updateFromControls();
  });
  app.querySelector("#cache-reset").addEventListener("click", () => {
    controls.search.value = "";
    controls.state.value = "all";
    controls.quality.value = "all";
    controls.sort.value = "id";
    controls.direction.dataset.direction = "asc";
    controls.direction.textContent = "↑";
    updateFromControls();
  });
  app.querySelector("#cache-refresh").addEventListener("click", () => refresh().catch((error) => {
    setProgress(`${t("apiError")}: ${error.message}`);
  }));
  app.querySelector("#check-availability").addEventListener("click", () => checkAvailability().catch((error) => {
    setProgress(`${t("apiError")}: ${error.message}`);
  }));
  app.querySelector("#delete-temporary").addEventListener("click", () => deleteTemporary().catch((error) => {
    setProgress(`${t("apiError")}: ${error.message}`);
  }));
  controls.loadMore.addEventListener("click", () => {
    visibleCount += PAGE_SIZE;
    renderEntries();
  });
  controls.grid.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const card = button.closest("[data-cache-id]");
    const entry = entries.find((candidate) => candidate.cacheId === card?.dataset.cacheId);
    if (!entry) return;
    const operation = button.dataset.action === "delete" ? deleteEntry(entry) : showDetails(entry);
    operation.catch((error) => setProgress(`${t("apiError")}: ${error.message}`));
  });

  await refresh();
}
