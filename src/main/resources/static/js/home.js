// Home page: search type tabs, Mapbox address suggestions and site name suggestions.
// Loaded as a file so the page needs no inline script.

// Search type tabs (click, plus Left/Right/Home/End keys as in the ARIA tabs pattern)
(() => {
  document.documentElement.classList.add('js-enabled');

  const tabs = Array.from(document.querySelectorAll('.tt-segmented .nav-link[data-search-tab-target]'));
  const panels = Array.from(document.querySelectorAll('.tt-search-panel'));
  const activateTab = (targetId) => {
    tabs.forEach((tab) => {
      const isActive = tab.dataset.searchTabTarget === targetId;
      tab.classList.toggle('active', isActive);
      tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
      tab.setAttribute('tabindex', isActive ? '0' : '-1');
    });
    panels.forEach((panel) => {
      panel.classList.toggle('is-active', panel.id === targetId);
    });
  };

  tabs.forEach((tab, index) => {
    tab.setAttribute('tabindex', tab.classList.contains('active') ? '0' : '-1');
    tab.addEventListener('click', () => activateTab(tab.dataset.searchTabTarget));
    tab.addEventListener('keydown', (event) => {
      let next = -1;
      if (event.key === 'ArrowRight') {
        next = (index + 1) % tabs.length;
      } else if (event.key === 'ArrowLeft') {
        next = (index - 1 + tabs.length) % tabs.length;
      } else if (event.key === 'Home') {
        next = 0;
      } else if (event.key === 'End') {
        next = tabs.length - 1;
      }
      if (next < 0) {
        return;
      }
      event.preventDefault();
      activateTab(tabs[next].dataset.searchTabTarget);
      tabs[next].focus();
    });
  });
})();

// Address suggestions from Mapbox (only when the page carries a public token)
(() => {
  const pane = document.querySelector('.tt-primary-lookup-pane[data-mapbox-token]');
  const input = document.getElementById('lookup-address');
  const suggestionPanel = document.getElementById('lookup-address-suggestions');
  const token = pane?.dataset.mapboxToken?.trim();
  if (!pane || !input || !suggestionPanel || !token) {
    return;
  }

  let debounceHandle;
  let abortController;
  let activeIndex = -1;
  let suggestions = [];

  const sessionToken = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID()
          : String(Date.now());

  const clearSuggestions = () => {
    suggestions = [];
    activeIndex = -1;
    suggestionPanel.innerHTML = '';
    suggestionPanel.classList.add('d-none');
    input.setAttribute('aria-expanded', 'false');
  };

  const suggestionLabel = (feature) =>
          feature?.properties?.full_address
          || feature?.place_formatted
          || feature?.properties?.name
          || feature?.name_preferred
          || '';

  const setActive = (nextIndex) => {
    activeIndex = nextIndex;
    Array.from(suggestionPanel.querySelectorAll('.tt-address-suggestion')).forEach((button, index) => {
      button.classList.toggle('is-active', index === activeIndex);
      button.setAttribute('aria-selected', index === activeIndex ? 'true' : 'false');
    });
  };

  const applySuggestion = (feature) => {
    const label = suggestionLabel(feature);
    if (!label) {
      return;
    }
    input.value = label;
    clearSuggestions();
  };

  const renderSuggestions = (features) => {
    suggestions = features.filter((feature) => suggestionLabel(feature));
    suggestionPanel.innerHTML = '';
    activeIndex = -1;

    if (suggestions.length === 0) {
      suggestionPanel.classList.add('d-none');
      input.setAttribute('aria-expanded', 'false');
      return;
    }

    suggestions.forEach((feature, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'tt-address-suggestion';
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', 'false');
      const text = document.createElement('span');
      text.className = 'tt-address-suggestion-text';
      text.textContent = suggestionLabel(feature);
      button.appendChild(text);
      button.addEventListener('mousedown', (event) => {
        event.preventDefault();
        applySuggestion(feature);
      });
      button.addEventListener('mouseenter', () => setActive(index));
      suggestionPanel.appendChild(button);
    });

    suggestionPanel.classList.remove('d-none');
    input.setAttribute('aria-expanded', 'true');
  };

  const fetchSuggestions = async (query) => {
    if (abortController) {
      abortController.abort();
    }

    abortController = new AbortController();

    const params = new URLSearchParams({
      q: query,
      access_token: token,
      autocomplete: 'true',
      country: 'US',
      types: 'address',
      limit: '5',
      session_token: sessionToken
    });

    const response = await fetch(`https://api.mapbox.com/search/geocode/v6/forward?${params.toString()}`, {
      method: 'GET',
      signal: abortController.signal
    });

    if (!response.ok) {
      throw new Error(`Mapbox suggestion request failed: ${response.status}`);
    }

    const payload = await response.json();
    renderSuggestions(Array.isArray(payload.features) ? payload.features : []);
  };

  input.addEventListener('input', () => {
    const query = input.value.trim();
    window.clearTimeout(debounceHandle);

    if (query.length < 5) {
      clearSuggestions();
      return;
    }

    debounceHandle = window.setTimeout(async () => {
      try {
        await fetchSuggestions(query);
      } catch (error) {
        if (error.name !== 'AbortError') {
          clearSuggestions();
        }
      }
    }, 250);
  });

  input.addEventListener('keydown', (event) => {
    if (suggestions.length === 0) {
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive(activeIndex < suggestions.length - 1 ? activeIndex + 1 : 0);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive(activeIndex > 0 ? activeIndex - 1 : suggestions.length - 1);
      return;
    }

    if (event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      applySuggestion(suggestions[activeIndex]);
      return;
    }

    if (event.key === 'Escape') {
      clearSuggestions();
    }
  });

  input.addEventListener('blur', () => {
    window.setTimeout(clearSuggestions, 120);
  });

  document.addEventListener('click', (event) => {
    if (!pane.contains(event.target)) {
      clearSuggestions();
    }
  });
})();
// Site name and EPA ID suggestions from /api/site-search/suggestions
(() => {
  const pane = document.querySelector('.tt-site-search-pane');
  const input = document.getElementById('site-search-query');
  const suggestionPanel = document.getElementById('site-search-suggestions');
  if (!pane || !input || !suggestionPanel) {
    return;
  }

  let debounceHandle;
  let abortController;
  let activeIndex = -1;
  let suggestions = [];

  const clearSuggestions = () => {
    suggestions = [];
    activeIndex = -1;
    suggestionPanel.innerHTML = '';
    suggestionPanel.classList.add('d-none');
    input.setAttribute('aria-expanded', 'false');
  };

  const setActive = (nextIndex) => {
    activeIndex = nextIndex;
    Array.from(suggestionPanel.querySelectorAll('.tt-address-suggestion')).forEach((button, index) => {
      button.classList.toggle('is-active', index === activeIndex);
      button.setAttribute('aria-selected', index === activeIndex ? 'true' : 'false');
    });
  };

  const goToSite = (suggestion) => {
    if (!suggestion?.epaId) {
      return;
    }
    window.location.assign(`/site/${encodeURIComponent(suggestion.epaId)}`);
  };

  const renderSuggestions = (items) => {
    suggestions = Array.isArray(items) ? items.filter((item) => item?.epaId && item?.siteName) : [];
    suggestionPanel.innerHTML = '';
    activeIndex = -1;

    if (suggestions.length === 0) {
      suggestionPanel.classList.add('d-none');
      input.setAttribute('aria-expanded', 'false');
      return;
    }

    suggestions.forEach((item, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'tt-address-suggestion';
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', 'false');

      const text = document.createElement('span');
      text.className = 'tt-address-suggestion-text';
      text.textContent = item.siteName;
      button.appendChild(text);

      const meta = document.createElement('span');
      meta.className = 'tt-address-suggestion-meta';
      meta.textContent = `${item.stateCode || 'State not reported'} | EPA ID ${item.epaId}`;
      button.appendChild(meta);

      button.addEventListener('mousedown', (event) => {
        event.preventDefault();
        goToSite(item);
      });
      button.addEventListener('mouseenter', () => setActive(index));
      suggestionPanel.appendChild(button);
    });

    suggestionPanel.classList.remove('d-none');
    input.setAttribute('aria-expanded', 'true');
  };

  const fetchSuggestions = async (query) => {
    if (abortController) {
      abortController.abort();
    }

    abortController = new AbortController();
    const params = new URLSearchParams({ q: query });
    const response = await fetch(`/api/site-search/suggestions?${params.toString()}`, {
      method: 'GET',
      signal: abortController.signal
    });

    if (!response.ok) {
      throw new Error(`Site suggestion request failed: ${response.status}`);
    }

    renderSuggestions(await response.json());
  };

  input.addEventListener('input', () => {
    const query = input.value.trim();
    window.clearTimeout(debounceHandle);

    if (query.length < 2) {
      clearSuggestions();
      return;
    }

    debounceHandle = window.setTimeout(async () => {
      try {
        await fetchSuggestions(query);
      } catch (error) {
        if (error.name !== 'AbortError') {
          clearSuggestions();
        }
      }
    }, 250);
  });

  input.addEventListener('keydown', (event) => {
    if (suggestions.length === 0) {
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive(activeIndex < suggestions.length - 1 ? activeIndex + 1 : 0);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive(activeIndex > 0 ? activeIndex - 1 : suggestions.length - 1);
      return;
    }

    if (event.key === 'Enter' && activeIndex >= 0) {
      event.preventDefault();
      goToSite(suggestions[activeIndex]);
      return;
    }

    if (event.key === 'Escape') {
      clearSuggestions();
    }
  });

  input.addEventListener('blur', () => {
    window.setTimeout(clearSuggestions, 120);
  });

  document.addEventListener('click', (event) => {
    if (!pane.contains(event.target)) {
      clearSuggestions();
    }
  });
})();
