#!/usr/bin/env python3
"""
Crunchyroll headless script for Java integration.
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

# Add StreamingCommunity to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'Downloader', 'StreamingCommunity', 'StreamingCommunity-main'))

# Import after path setup
from StreamingCommunity.Api.Site.crunchyroll.site import title_search, media_search_manager
from StreamingCommunity.Api.Site.crunchyroll.util.ScrapeSerie import GetSerieInfo
from StreamingCommunity.Api.Site.crunchyroll.series import download_video
from StreamingCommunity.Api.Site.crunchyroll.film import download_film
from StreamingCommunity.Api.Template.Class.SearchType import MediaItem
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
    """Search for content on Crunchyroll"""
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
        for i in range(media_search_manager.get_length()):
            item = media_search_manager.get(i)
            results.append({
                'id': getattr(item, 'id', '') or '',
                'title': getattr(item, 'name', 'Unknown'),
                'type': getattr(item, 'type', 'tv'),
                'year': str(getattr(item, 'date', '') or ''),
                'source': 'Crunchyroll',
                'sourceAlias': 'Crunchyroll',
                'url': getattr(item, 'url', '')
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


def cmd_list_episodes(series_id):
    """List all episodes for a series"""
    try:
        # Redirect stdout/stderr to suppress prints
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            scraper = GetSerieInfo(series_id)
            scraper.collect_season()
            series_name = scraper.series_name
            
            seasons = []
            for season in scraper.seasons_manager.seasons:
                season_num = season.number
                # Fetch episodes for this season
                episode_list = scraper._fetch_episodes_for_season(season_num)
                
                episodes = []
                for ep in episode_list:
                    episodes.append({
                        'id': ep.get('url', '').split('/')[-1] if ep.get('url') else '',
                        'season': str(season_num),
                        'episode': str(ep.get('number', '')),
                        'title': ep.get('name', f'Episode {ep.get("number", "")}'),
                        'duration': ep.get('duration', 0)
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
                def __init__(self, item_url, name, date=''):
                    self.url = item_url
                    self.name = name
                    self.date = date
                
                def get(self, key, default=None):
                    return getattr(self, key, default)
            
            fake_item = FakeMediaItem(url, title)
            
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


def cmd_download_episode(series_id, season, episode, output_dir):
    """Download a specific episode"""
    try:
        if not season or not episode:
            raise ValueError(f"Invalid season or episode: season='{season}', episode='{episode}'")
        
        os_module.makedirs(output_dir, exist_ok=True)
        
        # Redirect stdout/stderr
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)
        
        try:
            scraper = GetSerieInfo(series_id)
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
            'message': 'Usage: crunchyroll_headless.py <command> [args]'
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
            print(json.dumps({'status': 'error', 'message': 'Missing series_id'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2])
        
    elif command == 'download-film':
        if len(sys.argv) < 5:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url title output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_film(sys.argv[2], sys.argv[3], sys.argv[4])
        
    elif command == 'download-episode':
        if len(sys.argv) < 6:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: series_id season episode output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
