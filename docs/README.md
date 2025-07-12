# Excalibase GraphQL Documentation

This directory contains the source files for the Excalibase GraphQL documentation website, built with [MkDocs Material](https://squidfunk.github.io/mkdocs-material/).

## 🌐 Live Documentation

Visit the documentation at: **https://excalibase.github.io/excalibase-graphql/**

## 🛠️ Local Development

### Prerequisites

- Python 3.11+
- pip

### Setup

1. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

2. **Serve locally:**
   ```bash
   mkdocs serve
   ```

3. **Access locally:**
   ```
   http://localhost:8000
   ```

### Building

To build the static site:

```bash
mkdocs build
```

The generated site will be in the `site/` directory.

## 📁 Structure

```
docs/
├── index.md                 # Homepage
├── quickstart/              # Getting started guides
├── api/                     # API reference
├── features/                # Feature documentation
├── examples/                # Usage examples
├── testing/                 # Testing documentation
├── deployment/              # Deployment guides
├── stylesheets/            # Custom CSS
├── javascripts/            # Custom JavaScript
└── overrides/              # Theme overrides
```

## 🎨 Customization

### CSS

Custom styles are in `stylesheets/extra.css` and include:

- Enhanced code blocks with syntax highlighting
- Feature cards and grids
- Performance metrics styling
- Test coverage badges
- API endpoint styling

### JavaScript

Custom JavaScript is in `javascripts/extra.js` and includes:

- Copy-to-clipboard functionality
- Smooth scrolling
- Performance metrics animations
- Enhanced table responsiveness

## 📝 Contributing

1. **Edit documentation files** in the `docs/` directory
2. **Test locally** with `mkdocs serve`
3. **Commit changes** - GitHub Actions will automatically deploy
4. **Check the live site** at https://excalibase.github.io/excalibase-graphql/

## 🚀 Deployment

The documentation is automatically deployed to GitHub Pages via GitHub Actions when changes are pushed to the `main` branch.

The deployment workflow (`.github/workflows/docs.yml`) handles:

- Building the MkDocs site
- Publishing to GitHub Pages
- Caching dependencies for faster builds

## 🔧 Configuration

The main configuration is in `mkdocs.yml` at the repository root. Key features enabled:

- **Material theme** with dark/light mode toggle
- **Navigation tabs** for better organization
- **Search functionality** with highlighting
- **Code copying** with syntax highlighting
- **Git revision dates** for page freshness
- **Social links** to GitHub and Docker Hub

## 📚 MkDocs Material Features

This documentation uses many advanced MkDocs Material features:

- **Admonitions** for notes, warnings, and tips
- **Code blocks** with syntax highlighting
- **Tables** with sorting and filtering
- **Tabs** for organizing content
- **Icons and emojis** for visual appeal
- **Dark/light theme** toggle
- **Mobile responsive** design

## 🎯 Writing Tips

- Use **admonitions** for important information:
  ```markdown
  !!! note "Title"
      Your note content here
  ```

- Create **feature cards** with custom CSS:
  ```html
  <div class="feature-grid">
  <div class="feature-card">
  <h3>Feature Name</h3>
  <p>Description</p>
  </div>
  </div>
  ```

- Add **performance metrics** with styling:
  ```html
  <span class="perf-metric">< 200ms</span>
  ```

- Use **test badges** for coverage:
  ```html
  <span class="test-badge security">13+ tests</span>
  ```

## 🆘 Troubleshooting

### Common Issues

- **Build failures**: Check Python version and dependencies
- **Theme not loading**: Ensure `mkdocs-material` is installed
- **CSS not applying**: Check file paths in `mkdocs.yml`
- **Local server not starting**: Try `mkdocs serve --dev-addr=0.0.0.0:8000`

### Getting Help

- Check the [MkDocs Material documentation](https://squidfunk.github.io/mkdocs-material/)
- Review the [GitHub Issues](https://github.com/excalibase/excalibase-graphql/issues)
- Ask questions in our [Discord community](https://discord.gg/excalibase) 