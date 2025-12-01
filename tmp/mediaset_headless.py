#!/usr/bin/env python3
"""
Mediaset Infinity headless script for Java integration.
Provides JSON-based API for search, list episodes, and download.
"""

import sys
import os
import json
import io
import logging

# Disable ANSI colors completely
os.environ['NO_COLOR'] = '1'
os.environ['TERM'] = 'dumb'
os.environ['FORCE_COLOR'] = '0'

# Suppress all logging to avoid contaminating JSON output
logging.basicConfig(level=logging.CRITICAL)
logging.getLogger().setLevel(logging.CRITICAL)

# Redirect warnings
import warnings
warnings.filterwarnings("ignore")

# Redirect stderr and stdout temporarily during imports
old_stderr = sys.stderr
old_stdout = sys.stdout
sys.stderr = io.StringIO()
sys.stdout = io.StringIO()

# Add StreamingCommunity to path (same as raiplay_headless.py)
# Add StreamingCommunity to path - try multiple locations
script_dir = os.path.dirname(os.path.abspath(__file__))
possible_paths = [
    os.path.join(script_dir, '..', 'StreamingCommunity'),
    os.path.join(script_dir, '..', 'Downloader', 'StreamingCommunity', 'StreamingCommunity-main'),
    os.path.join(os.getcwd(), 'StreamingCommunity'),
]
for p in possible_paths:
    if os.path.exists(p):
        sys.path.insert(0, p)
        break

# Import after path setup
from StreamingCommunity.Api.Site.mediasetinfinity.site import title_search, media_search_manager
from StreamingCommunity.Api.Site.mediasetinfinity.util.ScrapeSerie import GetSerieInfo
from StreamingCommunity.Api.Site.mediasetinfinity.series import download_video
from StreamingCommunity.Api.Site.mediasetinfinity.film import download_film
from StreamingCommunity.Api.Site.mediasetinfinity.util.get_license import get_bearer_token
import os as os_module

# Restore stderr and stdout
sys.stderr = old_stderr
sys.stdout = old_stdout

# Disable rich console globally
try:
    from rich.console import Console
    Console.print = lambda self, *args, **kwargs: None
except:
    pass


def cmd_search(query):
    """Search for content on Mediaset Infinity"""
    try:
        # Redirect stdout/stderr to suppress prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            count = title_search(query)
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        results = []
        for i in range(count):
            item = media_search_manager.get(i)
            results.append({
                'id': item.id,
                'title': item.name,
                'type': item.type,
                'year': str(item.date) if item.date else '',
                'source': 'MediasetInfinity',
                'sourceAlias': 'Mediaset',
                'url': item.url
            })
        
        print(json.dumps({
            'status': 'ok',
            'count': len(results),
            'results': results
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_list_episodes(url):
    """List all episodes for a series"""
    try:
        # Redirect stdout/stderr to suppress prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            scraper = GetSerieInfo(url)
            scraper.collect_season()
            series_name = scraper.series_name
            
            seasons = []
            for i, season in enumerate(scraper.seasons_manager.seasons):
                season_num = i + 1
                episodes = []
                for j, ep in enumerate(season.episodes.episodes):
                    # ep can be Episode object or dict
                    if hasattr(ep, 'id'):
                        ep_id = ep.id
                        ep_title = getattr(ep, 'name', None) or getattr(ep, 'title', f'Episode {j+1}')
                        ep_duration = getattr(ep, 'duration', 0)
                    else:
                        ep_id = ep.get('id', '')
                        ep_title = ep.get('title', ep.get('name', f'Episode {j+1}'))
                        ep_duration = ep.get('duration', 0)
                    
                    episodes.append({
                        'id': ep_id,
                        'season': str(season_num),
                        'episode': str(j + 1),
                        'title': ep_title,
                        'duration': ep_duration
                    })
                seasons.append({
                    'season': str(season_num),
                    'episodes': episodes
                })
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'series_name': series_name,
            'seasons': seasons
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_download_film(url, title, output_dir):
    """Download a film"""
    try:
        os_module.makedirs(output_dir, exist_ok=True)
        
        # Initialize bearer token before download
        get_bearer_token()
        
        # Redirect stdout/stderr
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)
        
        try:
            # Create fake MediaItem
            class FakeMediaItem:
                def __init__(self, item_id, name, item_url, date=''):
                    self.id = item_id
                    self.name = name
                    self.url = item_url
                    self.date = date
            
            # Extract ID from URL (format: /movie/title_GUID)
            item_id = url.split('_')[-1] if '_' in url else url.split('/')[-1]
            fake_item = FakeMediaItem(item_id, title, url)
            
            path, stopped = download_film(fake_item)
            
            if not path or not os_module.path.exists(path):
                raise Exception("Download failed: file not created")
            if os_module.path.getsize(path) == 0:
                raise Exception("Download failed: file is empty")
            if stopped:
                raise Exception("Download was stopped")
            
            # Move to output_dir with clean name
            ext = os_module.path.splitext(path)[1]
            new_filename = f"{title}{ext}"
            new_path = os_module.path.join(output_dir, new_filename)
            
            import shutil
            shutil.move(path, new_path)
            
            # Cleanup empty dirs
            try:
                parent = os_module.path.dirname(path)
                while parent and parent != output_dir:
                    if os_module.path.isdir(parent) and not os_module.listdir(parent):
                        os_module.rmdir(parent)
                    parent = os_module.path.dirname(parent)
            except:
                pass
                
        finally:
            os_module.chdir(original_dir)
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': new_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_download_episode(url, season, episode, output_dir):
    """Download a specific episode"""
    try:
        if not season or not episode:
            raise ValueError(f"Invalid season or episode: season='{season}', episode='{episode}'")
        
        os_module.makedirs(output_dir, exist_ok=True)
        
        # Initialize bearer token before download
        get_bearer_token()
        
        # Redirect stdout/stderr
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)
        
        try:
            scraper = GetSerieInfo(url)
            scraper.collect_season()
            series_name = scraper.series_name
            
            season_int = int(season)
            episode_int = int(episode)
            
            path, stopped = download_video(season_int, episode_int, scraper)
            
            if not path or not os_module.path.exists(path):
                raise Exception("Download failed: file not created")
            if os_module.path.getsize(path) == 0:
                raise Exception("Download failed: file is empty")
            if stopped:
                raise Exception("Download was stopped")
            
            # Move and rename: "Series Name 01x02.ext"
            ext = os_module.path.splitext(path)[1]
            new_filename = f"{series_name} {str(season_int).zfill(2)}x{str(episode_int).zfill(2)}{ext}"
            new_path = os_module.path.join(output_dir, new_filename)
            
            import shutil
            shutil.move(path, new_path)
            
            # Cleanup empty dirs
            try:
                parent = os_module.path.dirname(path)
                while parent and parent != output_dir:
                    if os_module.path.isdir(parent) and not os_module.listdir(parent):
                        os_module.rmdir(parent)
                    parent = os_module.path.dirname(parent)
            except:
                pass
                
        finally:
            os_module.chdir(original_dir)
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': new_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def main():
    if len(sys.argv) < 2:
        print(json.dumps({
            'status': 'error',
            'message': 'Usage: mediaset_headless.py <command> [args]'
        }), file=sys.stderr)
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'search':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing query'}), file=sys.stderr)
            sys.exit(1)
        cmd_search(sys.argv[2])
        
    elif command == 'list-episodes':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing URL'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2])
        
    elif command == 'download-film':
        if len(sys.argv) < 5:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url title output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_film(sys.argv[2], sys.argv[3], sys.argv[4])
        
    elif command == 'download-episode':
        if len(sys.argv) < 6:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url season episode output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
