// Custom JavaScript for Excalibase GraphQL Documentation

document.addEventListener('DOMContentLoaded', function() {
  // Add copy button functionality to code blocks
  const codeBlocks = document.querySelectorAll('pre code');
  
  codeBlocks.forEach(function(block) {
    const wrapper = block.parentElement;
    const button = document.createElement('button');
    button.className = 'md-clipboard md-icon';
    button.innerHTML = '<svg viewBox="0 0 24 24"><path d="M19,21H8V7H19M19,5H8A2,2 0 0,0 6,7V21A2,2 0 0,0 8,23H19A2,2 0 0,0 21,21V7A2,2 0 0,0 19,5M16,1H4A2,2 0 0,0 2,3V17H4V3H16V1Z"></path></svg>';
    button.title = 'Copy to clipboard';
    
    button.addEventListener('click', function() {
      const textToCopy = block.textContent;
      navigator.clipboard.writeText(textToCopy).then(function() {
        button.classList.add('md-clipboard--copied');
        button.innerHTML = '<svg viewBox="0 0 24 24"><path d="M21,7L9,19L3.5,13.5L4.91,12.09L9,16.17L19.59,5.59L21,7Z"></path></svg>';
        
        setTimeout(function() {
          button.classList.remove('md-clipboard--copied');
          button.innerHTML = '<svg viewBox="0 0 24 24"><path d="M19,21H8V7H19M19,5H8A2,2 0 0,0 6,7V21A2,2 0 0,0 8,23H19A2,2 0 0,0 21,21V7A2,2 0 0,0 19,5M16,1H4A2,2 0 0,0 2,3V17H4V3H16V1Z"></path></svg>';
        }, 2000);
      });
    });
    
    wrapper.appendChild(button);
  });
  
  // Add smooth scrolling for anchor links
  document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
      e.preventDefault();
      const target = document.querySelector(this.getAttribute('href'));
      if (target) {
        target.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });
      }
    });
  });
  
  // Add enhanced table responsiveness
  const tables = document.querySelectorAll('table');
  tables.forEach(table => {
    if (!table.parentElement.classList.contains('md-typeset')) {
      const wrapper = document.createElement('div');
      wrapper.className = 'table-wrapper';
      wrapper.style.overflowX = 'auto';
      table.parentElement.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    }
  });
  
  // Add performance metrics animation
  const perfMetrics = document.querySelectorAll('.perf-metric');
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.style.animation = 'fadeInUp 0.5s ease-out';
      }
    });
  });
  
  perfMetrics.forEach(metric => {
    observer.observe(metric);
  });
  
  // Add feature card hover effects
  const featureCards = document.querySelectorAll('.feature-card');
  featureCards.forEach(card => {
    card.addEventListener('mouseenter', function() {
      this.style.transform = 'translateY(-4px)';
      this.style.boxShadow = '0 8px 16px rgba(0, 0, 0, 0.15)';
    });
    
    card.addEventListener('mouseleave', function() {
      this.style.transform = 'translateY(0)';
      this.style.boxShadow = '0 2px 4px rgba(0, 0, 0, 0.1)';
    });
  });
  
  // Add keyboard navigation improvements
  document.addEventListener('keydown', function(e) {
    // Ctrl/Cmd + K to focus search
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      const searchInput = document.querySelector('input[type="search"]');
      if (searchInput) {
        searchInput.focus();
      }
    }
  });
  
  // Add external link indicators
  const externalLinks = document.querySelectorAll('a[href^="http"]:not([href*="excalibase.github.io"])');
  externalLinks.forEach(link => {
    link.classList.add('external-link');
    link.setAttribute('target', '_blank');
    link.setAttribute('rel', 'noopener noreferrer');
  });
});

// Add CSS animation keyframes
const style = document.createElement('style');
style.textContent = `
  @keyframes fadeInUp {
    from {
      opacity: 0;
      transform: translateY(20px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  
  .external-link::after {
    content: "↗";
    font-size: 0.8em;
    opacity: 0.7;
    margin-left: 0.2em;
  }
  
  .table-wrapper {
    margin: 1rem 0;
    border-radius: 0.5rem;
    overflow: hidden;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  }
`;
document.head.appendChild(style); 